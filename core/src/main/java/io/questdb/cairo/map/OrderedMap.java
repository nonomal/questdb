/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2024 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cairo.map;

import io.questdb.cairo.*;
import io.questdb.cairo.sql.Record;
import io.questdb.cairo.sql.RecordCursor;
import io.questdb.griffin.engine.LimitOverflowException;
import io.questdb.std.*;
import io.questdb.std.bytes.Bytes;
import io.questdb.std.str.Utf8Sequence;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static io.questdb.std.Numbers.MAX_SAFE_INT_POW_2;

/**
 * OrderedMap a.k.a. FastMap is a general purpose off-heap hash table used to store intermediate
 * data of join, group by, sample by queries, but not only. It provides {@link MapKey} and
 * {@link MapValue}, as well as {@link RecordCursor} interfaces for data access and modification.
 * The preferred way to create an OrderedMap is {@link MapFactory}.
 * <p>
 * Map iteration provided by {@link RecordCursor} preserves the key insertion order, hence
 * the ordered map name.
 * <strong>Important!</strong>
 * Key and value structures must match the ones provided via lists of columns ({@link ColumnTypes})
 * to the map constructor. Later put* calls made on {@link MapKey} and {@link MapValue} must match
 * the declared column types to guarantee memory access safety.
 * <p>
 * Keys may be var-size, i.e. a key may contain string or binary columns, while values are expected
 * to be fixed-size. Only insertions and updates operations are supported meaning that a key can't
 * be removed from the map once it was inserted.
 * <p>
 * The hash table is organized into two main parts:
 * <ul>
 * <li>1. Off-heap list for heap offsets and cached hash codes</li>
 * <li>2. Off-heap memory for key-value pairs a.k.a. "key memory"</li>
 * </ul>
 * The offset list contains [compressed_offset, hash code 32 LSBs] pairs. An offset value contains
 * an offset to the address of a key-value pair in the key memory compressed to an int. Key-value
 * pair addresses are 8 byte aligned, so an OrderedMap is capable of holding up to 32GB of data.
 * <p>
 * The offset list is used as a hash table with linear probing. So, a table resize allocates a new
 * offset list and copies offsets there while the key memory stays as is.
 * <p>
 * Key-value pairs stored in the key memory may have the following layout:
 * <pre>
 * |       length         | Key columns 0..K | Value columns 0..V |
 * +----------------------+------------------+--------------------+
 * |      4 bytes         |        -         |         -          |
 * +----------------------+------------------+--------------------+
 * </pre>
 * Length field is present for var-size keys only. It stores key length in bytes.
 */
public class OrderedMap implements Map, Reopenable {

    static final long VAR_KEY_HEADER_SIZE = 4;
    private static final long MAX_HEAP_SIZE = (Integer.toUnsignedLong(-1) - 1) << 3;
    private static final int MIN_KEY_CAPACITY = 16;
    private final OrderedMapCursor cursor;
    private final int heapMemoryTag;
    private final Key key;
    private final long keyOffset;
    // Set to -1 when key is var-size.
    private final long keySize;
    private final int listMemoryTag;
    private final double loadFactor;
    private final int maxResizes;
    private final MergeFunction mergeRef;
    private final OrderedMapRecord record;
    private final OrderedMapValue value;
    private final OrderedMapValue value2;
    private final OrderedMapValue value3;
    private final int valueColumnCount;
    private final long valueSize;
    private int free;
    private long heapLimit; // Heap memory limit pointer.
    private long heapSize;
    private long heapStart; // Heap memory start pointer.
    private int initialHeapSize;
    private int initialKeyCapacity;
    private long kPos;      // Current key-value memory pointer (contains searched key / pending key-value pair).
    private int keyCapacity;
    private int mask;
    private int nResizes;
    // Holds [compressed_offset, hash_code] pairs.
    // Offsets are shifted by +1 (0 -> 1, 1 -> 2, etc.), so that we fill the memory with 0.
    private DirectIntList offsets;
    private int size = 0;

    public OrderedMap(
            int heapSize,
            @Transient @NotNull ColumnTypes keyTypes,
            int keyCapacity,
            double loadFactor,
            int maxResizes
    ) {
        this(heapSize, keyTypes, null, keyCapacity, loadFactor, maxResizes);
    }

    public OrderedMap(
            int heapSize,
            @Transient @NotNull ColumnTypes keyTypes,
            @Transient @Nullable ColumnTypes valueTypes,
            int keyCapacity,
            double loadFactor,
            int maxResizes,
            int memoryTag
    ) {
        this(heapSize, keyTypes, valueTypes, keyCapacity, loadFactor, maxResizes, memoryTag, memoryTag);
    }

    public OrderedMap(
            int heapSize,
            @Transient @NotNull ColumnTypes keyTypes,
            @Transient @Nullable ColumnTypes valueTypes,
            int keyCapacity,
            double loadFactor,
            int maxResizes
    ) {
        this(heapSize, keyTypes, valueTypes, keyCapacity, loadFactor, maxResizes, MemoryTag.NATIVE_FAST_MAP, MemoryTag.NATIVE_FAST_MAP_INT_LIST);
    }

    OrderedMap(
            int heapSize,
            @NotNull @Transient ColumnTypes keyTypes,
            @Nullable @Transient ColumnTypes valueTypes,
            int keyCapacity,
            double loadFactor,
            int maxResizes,
            int heapMemoryTag,
            int listMemoryTag
    ) {
        assert heapSize > 3;
        assert loadFactor > 0 && loadFactor < 1d;

        this.heapMemoryTag = heapMemoryTag;
        this.listMemoryTag = listMemoryTag;
        initialHeapSize = heapSize;
        this.loadFactor = loadFactor;
        heapStart = kPos = Unsafe.malloc(this.heapSize = heapSize, heapMemoryTag);
        heapLimit = heapStart + heapSize;
        this.keyCapacity = (int) (keyCapacity / loadFactor);
        this.keyCapacity = this.initialKeyCapacity = Math.max(Numbers.ceilPow2(this.keyCapacity), MIN_KEY_CAPACITY);
        mask = this.keyCapacity - 1;
        free = (int) (this.keyCapacity * loadFactor);
        offsets = new DirectIntList((long) this.keyCapacity << 1, listMemoryTag);
        offsets.setPos((long) this.keyCapacity << 1);
        offsets.zero(0);
        nResizes = 0;
        this.maxResizes = maxResizes;

        final int keyColumnCount = keyTypes.getColumnCount();
        long keySize = 0;
        for (int i = 0; i < keyColumnCount; i++) {
            final int columnType = keyTypes.getColumnType(i);
            final int size = ColumnType.sizeOf(columnType);
            if (size > 0) {
                keySize += size;
            } else {
                keySize = -1;
                break;
            }
        }
        this.keySize = keySize;

        // Reserve 4 bytes for key length in case of var-size keys.
        keyOffset = keySize != -1 ? 0 : Integer.BYTES;

        long valueOffset = 0;
        long[] valueOffsets = null;
        long valueSize = 0;
        if (valueTypes != null) {
            valueColumnCount = valueTypes.getColumnCount();
            valueOffsets = new long[valueColumnCount];

            for (int i = 0; i < valueColumnCount; i++) {
                valueOffsets[i] = valueOffset;
                final int columnType = valueTypes.getColumnType(i);
                final int size = ColumnType.sizeOf(columnType);
                if (size <= 0) {
                    close();
                    throw CairoException.nonCritical().put("value type is not supported: ").put(ColumnType.nameOf(columnType));
                }
                valueOffset += size;
                valueSize += size;
            }
        } else {
            valueColumnCount = 0;
        }
        this.valueSize = valueSize;

        value = new OrderedMapValue(valueSize, valueOffsets);
        value2 = new OrderedMapValue(valueSize, valueOffsets);
        value3 = new OrderedMapValue(valueSize, valueOffsets);

        assert keySize + valueSize <= heapLimit - heapStart : "page size is too small to fit a single key";
        if (keySize == -1) {
            record = new OrderedMapVarSizeRecord(valueSize, valueOffsets, value, keyTypes, valueTypes);
            key = new VarSizeKey();
            mergeRef = this::mergeVarSizeKey;
        } else {
            record = new OrderedMapFixedSizeRecord(keySize, valueSize, valueOffsets, value, keyTypes, valueTypes);
            key = new FixedSizeKey();
            mergeRef = this::mergeFixedSizeKey;
        }
        cursor = new OrderedMapCursor(record, this);
    }

    @Override
    public void clear() {
        kPos = heapStart;
        free = (int) (keyCapacity * loadFactor);
        size = 0;
        offsets.zero(0);
        nResizes = 0;
    }

    @Override
    public void close() {
        Misc.free(offsets);
        if (heapStart != 0) {
            Unsafe.free(heapStart, heapSize, heapMemoryTag);
            heapLimit = heapStart = kPos = 0;
            free = 0;
            size = 0;
            heapSize = 0;
        }
    }

    public long getAppendOffset() {
        return kPos;
    }

    @Override
    public MapRecordCursor getCursor() {
        return cursor.init(heapStart, heapLimit, size);
    }

    @Override
    public long getHeapSize() {
        return heapLimit - heapStart;
    }

    @Override
    public int getKeyCapacity() {
        return keyCapacity;
    }

    @Override
    public MapRecord getRecord() {
        return record;
    }

    @Override
    public long getUsedHeapSize() {
        return kPos - heapStart;
    }

    public int getValueColumnCount() {
        return valueColumnCount;
    }

    @Override
    public void merge(Map srcMap, MapValueMergeFunction mergeFunc) {
        assert this != srcMap;
        if (srcMap.size() == 0) {
            return;
        }
        mergeRef.merge((OrderedMap) srcMap, mergeFunc);
    }

    @Override
    public void reopen(int keyCapacity, int heapSize) {
        if (heapStart == 0) {
            keyCapacity = (int) (keyCapacity / loadFactor);
            initialKeyCapacity = Math.max(Numbers.ceilPow2(keyCapacity), MIN_KEY_CAPACITY);
            initialHeapSize = heapSize;
            restoreInitialCapacity();
        }
    }

    public void reopen() {
        if (heapStart == 0) {
            // handles both mem and offsets
            restoreInitialCapacity();
        }
    }

    @Override
    public void restoreInitialCapacity() {
        if (heapSize != initialHeapSize || keyCapacity != initialKeyCapacity) {
            heapStart = kPos = Unsafe.realloc(heapStart, heapLimit - heapStart, heapSize = initialHeapSize, heapMemoryTag);
            heapLimit = heapStart + initialHeapSize;
            keyCapacity = initialKeyCapacity;
            keyCapacity = keyCapacity < MIN_KEY_CAPACITY ? MIN_KEY_CAPACITY : Numbers.ceilPow2(keyCapacity);
            mask = keyCapacity - 1;
            offsets.resetCapacity();
            offsets.setCapacity((long) keyCapacity << 1);
            offsets.setPos((long) keyCapacity << 1);

            clear();
        }
    }

    @Override
    public void setKeyCapacity(int newKeyCapacity) {
        long requiredCapacity = (long) (newKeyCapacity / loadFactor);
        if (requiredCapacity > MAX_SAFE_INT_POW_2) {
            throw CairoException.nonCritical().put("map capacity overflow");
        }
        rehash(Numbers.ceilPow2((int) requiredCapacity));
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public MapValue valueAt(long startAddress) {
        long keySize = this.keySize;
        if (keySize == -1) {
            keySize = Unsafe.getUnsafe().getInt(startAddress);
        }
        return valueOf(startAddress, startAddress + keyOffset + keySize, false, value);
    }

    @Override
    public MapKey withKey() {
        return key.init();
    }

    // Lowest 32 bits of hash code can be used to obtain an entry index since
    // maximum number of entries in the map is limited with 32-bit compressed offsets.
    private static int getHashCodeLo(DirectIntList offsets, int index) {
        return offsets.get(((long) index << 1) | 1);
    }

    private static long getOffset(int rawOffset) {
        return ((long) rawOffset - 1) << 3;
    }

    private static int getRawOffset(DirectIntList offsets, int index) {
        return offsets.get((long) index << 1);
    }

    private static void setHashCodeLo(DirectIntList offsets, int index, int hashCodeLo) {
        offsets.set(((long) index << 1) | 1, hashCodeLo);
    }

    private static void setOffset(DirectIntList offsets, int index, long offset) {
        offsets.set((long) index << 1, (int) ((offset >> 3) + 1));
    }

    private OrderedMapValue asNew(Key keyWriter, int index, int hashCodeLo, OrderedMapValue value) {
        // Align current pointer to 8 bytes, so that we can store compressed offsets.
        kPos = Bytes.align8b(keyWriter.appendAddress + valueSize);
        setOffset(offsets, index, keyWriter.startAddress - heapStart);
        setHashCodeLo(offsets, index, hashCodeLo);
        size++;
        if (--free == 0) {
            rehash();
        }
        return valueOf(keyWriter.startAddress, keyWriter.appendAddress, true, value);
    }

    private void mergeFixedSizeKey(OrderedMap srcMap, MapValueMergeFunction mergeFunc) {
        assert keySize >= 0;

        long entrySize = keySize + valueSize;
        long alignedEntrySize = Bytes.align8b(entrySize);

        // Calculating fixed-size key's hash code is cheap, so unlike mergeVarSizeKey,
        // here we iterate the heap, not the hash table.
        OUTER:
        for (long srcAddress = srcMap.heapStart; srcAddress < srcMap.kPos; srcAddress += alignedEntrySize) {
            long hashCode = Hash.hashMem64(srcAddress, keySize);
            int hashCodeLo = Numbers.decodeLowInt(hashCode);
            int index = hashCodeLo & mask;

            int destRawOffset;
            while ((destRawOffset = getRawOffset(offsets, index)) != 0) {
                long destAddress = heapStart + getOffset(destRawOffset);
                if (hashCodeLo == getHashCodeLo(offsets, index) && Vect.memeq(destAddress, srcAddress, keySize)) {
                    // Match found, merge values.
                    mergeFunc.merge(valueAt(destAddress), srcMap.valueAt(srcAddress));
                    continue OUTER;
                }
                index = (index + 1) & mask;
            }

            if (kPos + entrySize > heapLimit) {
                resize(entrySize, kPos);
            }
            Vect.memcpy(kPos, srcAddress, entrySize);
            setOffset(offsets, index, kPos - heapStart);
            setHashCodeLo(offsets, index, hashCodeLo);
            kPos += alignedEntrySize;
            size++;
            if (--free == 0) {
                rehash();
            }
        }
    }

    private void mergeVarSizeKey(OrderedMap srcMap, MapValueMergeFunction mergeFunc) {
        assert keySize == -1;

        OUTER:
        for (int i = 0, k = (int) (srcMap.offsets.size() >>> 1); i < k; i++) {
            int rawOffset = getRawOffset(srcMap.offsets, i);
            if (rawOffset == 0) {
                continue;
            }

            long srcAddress = srcMap.heapStart + getOffset(rawOffset);
            int srcKeySize = Unsafe.getUnsafe().getInt(srcAddress);
            int hashCodeLo = getHashCodeLo(srcMap.offsets, i);
            int index = hashCodeLo & mask;

            int destRawOffset;
            while ((destRawOffset = getRawOffset(offsets, index)) != 0) {
                long destAddress = heapStart + getOffset(destRawOffset);
                if (
                        hashCodeLo == getHashCodeLo(offsets, index)
                                && Unsafe.getUnsafe().getInt(destAddress) == srcKeySize
                                && Vect.memeq(destAddress + keyOffset, srcAddress + keyOffset, srcKeySize)
                ) {
                    // Match found, merge values.
                    mergeFunc.merge(valueAt(destAddress), srcMap.valueAt(srcAddress));
                    continue OUTER;
                }
                index = (index + 1) & mask;
            }

            long entrySize = keyOffset + srcKeySize + valueSize;
            if (kPos + entrySize > heapLimit) {
                resize(entrySize, kPos);
            }
            Vect.memcpy(kPos, srcAddress, entrySize);
            setOffset(offsets, index, kPos - heapStart);
            setHashCodeLo(offsets, index, hashCodeLo);
            kPos = Bytes.align8b(kPos + entrySize);
            size++;
            if (--free == 0) {
                rehash();
            }
        }
    }

    private OrderedMapValue probe0(Key keyWriter, int index, int hashCodeLo, long keySize, OrderedMapValue value) {
        int rawOffset;
        while ((rawOffset = getRawOffset(offsets, index = (index + 1) & mask)) != 0) {
            long offset = getOffset(rawOffset);
            if (hashCodeLo == getHashCodeLo(offsets, index) && keyWriter.eq(offset)) {
                long startAddress = heapStart + offset;
                return valueOf(startAddress, startAddress + keyOffset + keySize, false, value);
            }
        }
        return asNew(keyWriter, index, hashCodeLo, value);
    }

    private OrderedMapValue probeReadOnly(Key keyWriter, int index, int hashCodeLo, long keySize, OrderedMapValue value) {
        int rawOffset;
        while ((rawOffset = getRawOffset(offsets, index = (index + 1) & mask)) != 0) {
            long offset = getOffset(rawOffset);
            if (hashCodeLo == getHashCodeLo(offsets, index) && keyWriter.eq(offset)) {
                long startAddress = heapStart + offset;
                return valueOf(startAddress, startAddress + keyOffset + keySize, false, value);
            }
        }
        return null;
    }

    private void rehash() {
        rehash((long) keyCapacity << 1);
    }

    private void rehash(long newKeyCapacity) {
        if (newKeyCapacity > MAX_SAFE_INT_POW_2) {
            throw CairoException.nonCritical().put("map capacity overflow");
        }
        if (newKeyCapacity <= keyCapacity) {
            return;
        }

        mask = (int) newKeyCapacity - 1;
        DirectIntList newOffsets = new DirectIntList(newKeyCapacity << 1, listMemoryTag);
        newOffsets.setPos(newKeyCapacity << 1);
        newOffsets.zero(0);

        for (int i = 0, k = (int) (offsets.size() >>> 1); i < k; i++) {
            int rawOffset = getRawOffset(offsets, i);
            if (rawOffset == 0) {
                continue;
            }
            long offset = getOffset(rawOffset);
            int hashCodeLo = getHashCodeLo(offsets, i);
            int index = hashCodeLo & mask;
            while (getRawOffset(newOffsets, index) != 0) {
                index = (index + 1) & mask;
            }
            setOffset(newOffsets, index, offset);
            setHashCodeLo(newOffsets, index, hashCodeLo);
        }
        offsets.close();
        offsets = newOffsets;
        free += (int) ((newKeyCapacity - keyCapacity) * loadFactor);
        keyCapacity = (int) newKeyCapacity;
    }

    // Returns delta between new and old heapStart addresses.
    private long resize(long entrySize, long appendAddress) {
        assert appendAddress >= heapStart;
        if (nResizes < maxResizes) {
            nResizes++;
            long kCapacity = (heapLimit - heapStart) << 1;
            long target = appendAddress + entrySize - heapStart;
            if (kCapacity < target) {
                kCapacity = Numbers.ceilPow2(target);
            }
            if (kCapacity > MAX_HEAP_SIZE) {
                throw LimitOverflowException.instance().put("limit of ").put(MAX_HEAP_SIZE).put(" memory exceeded in OrderedMap");
            }
            long kAddress = Unsafe.realloc(heapStart, heapSize, kCapacity, heapMemoryTag);

            this.heapSize = kCapacity;
            long delta = kAddress - heapStart;
            kPos += delta;
            assert kPos > 0;

            this.heapStart = kAddress;
            this.heapLimit = kAddress + kCapacity;

            return delta;
        } else {
            throw LimitOverflowException.instance().put("limit of ").put(maxResizes).put(" resizes exceeded in OrderedMap");
        }
    }

    private OrderedMapValue valueOf(long startAddress, long valueAddress, boolean newValue, OrderedMapValue value) {
        return value.of(startAddress, valueAddress, heapLimit, newValue);
    }

    long keySize() {
        return keySize;
    }

    long valueSize() {
        return valueSize;
    }

    @FunctionalInterface
    private interface MergeFunction {
        void merge(OrderedMap srcMap, MapValueMergeFunction mergeFunc);
    }

    class FixedSizeKey extends Key {

        @Override
        public long commit() {
            assert appendAddress <= startAddress + keySize;
            return keySize;
        }

        @Override
        public void copyFrom(MapKey srcKey) {
            FixedSizeKey srcFastKey = (FixedSizeKey) srcKey;
            copyFromRawKey(srcFastKey.startAddress, keySize);
        }

        @Override
        public void copyFromRawKey(long srcPtr, long srcSize) {
            assert srcSize == keySize;
            Vect.memcpy(appendAddress, srcPtr, srcSize);
            appendAddress += srcSize;
        }

        @Override
        public long hash() {
            return Hash.hashMem64(startAddress, keySize);
        }

        public FixedSizeKey init() {
            super.init();
            checkCapacity(keySize);
            return this;
        }

        @Override
        public void putBin(BinarySequence value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putBool(boolean value) {
            Unsafe.getUnsafe().putByte(appendAddress, (byte) (value ? 1 : 0));
            appendAddress += 1L;
        }

        @Override
        public void putByte(byte value) {
            Unsafe.getUnsafe().putByte(appendAddress, value);
            appendAddress += 1L;
        }

        @Override
        public void putChar(char value) {
            Unsafe.getUnsafe().putChar(appendAddress, value);
            appendAddress += 2L;
        }

        @Override
        public void putDate(long value) {
            putLong(value);
        }

        @Override
        public void putDouble(double value) {
            Unsafe.getUnsafe().putDouble(appendAddress, value);
            appendAddress += 8L;
        }

        @Override
        public void putFloat(float value) {
            Unsafe.getUnsafe().putFloat(appendAddress, value);
            appendAddress += 4L;
        }

        @Override
        public void putIPv4(int value) {
            putInt(value);
        }

        @Override
        public void putInt(int value) {
            Unsafe.getUnsafe().putInt(appendAddress, value);
            appendAddress += 4L;
        }

        @Override
        public void putLong(long value) {
            Unsafe.getUnsafe().putLong(appendAddress, value);
            appendAddress += 8L;
        }

        @Override
        public void putLong128(long lo, long hi) {
            Unsafe.getUnsafe().putLong(appendAddress, lo);
            Unsafe.getUnsafe().putLong(appendAddress + Long.BYTES, hi);
            appendAddress += 16L;
        }

        @Override
        public void putLong256(Long256 value) {
            Unsafe.getUnsafe().putLong(appendAddress, value.getLong0());
            Unsafe.getUnsafe().putLong(appendAddress + Long.BYTES, value.getLong1());
            Unsafe.getUnsafe().putLong(appendAddress + Long.BYTES * 2, value.getLong2());
            Unsafe.getUnsafe().putLong(appendAddress + Long.BYTES * 3, value.getLong3());
            appendAddress += 32L;
        }

        @Override
        public void putLong256(long l0, long l1, long l2, long l3) {
            Unsafe.getUnsafe().putLong(appendAddress, l0);
            Unsafe.getUnsafe().putLong(appendAddress + Long.BYTES, l1);
            Unsafe.getUnsafe().putLong(appendAddress + Long.BYTES * 2, l2);
            Unsafe.getUnsafe().putLong(appendAddress + Long.BYTES * 3, l3);
            appendAddress += 32L;
        }

        @Override
        public void putShort(short value) {
            Unsafe.getUnsafe().putShort(appendAddress, value);
            appendAddress += 2L;
        }

        @Override
        public void putStr(CharSequence value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putStr(CharSequence value, int lo, int hi) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void putTimestamp(long value) {
            putLong(value);
        }

        @Override
        public void putVarchar(Utf8Sequence value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void skip(int bytes) {
            appendAddress += bytes;
        }

        @Override
        protected boolean eq(long offset) {
            return Vect.memeq(heapStart + offset, startAddress, keySize);
        }
    }

    abstract class Key implements MapKey {
        protected long appendAddress;
        protected long startAddress;

        @Override
        public MapValue createValue() {
            long keySize = commit();
            // calculate hash remembering "key" structure
            // [ key size | key block | value block ]
            long hashCode = hash();
            return createValue(keySize, hashCode);
        }

        @Override
        public MapValue createValue(long hashCode) {
            long keySize = commit();
            return createValue(keySize, hashCode);
        }

        @Override
        public MapValue findValue() {
            return findValue(value);
        }

        @Override
        public MapValue findValue2() {
            return findValue(value2);
        }

        @Override
        public MapValue findValue3() {
            return findValue(value3);
        }

        public Key init() {
            reset();
            return this;
        }

        @Override
        public void put(Record record, RecordSink sink) {
            sink.copy(record, this);
        }

        public abstract void putLong256(long l0, long l1, long l2, long l3);

        @Override
        public void putRecord(Record value) {
            // no-op
        }

        public void reset() {
            startAddress = kPos;
            appendAddress = kPos + keyOffset;
        }

        private MapValue createValue(long keySize, long hashCode) {
            int hashCodeLo = Numbers.decodeLowInt(hashCode);
            int index = hashCodeLo & mask;

            int rawOffset = getRawOffset(offsets, index);
            long offset;
            if (rawOffset == 0) {
                return asNew(this, index, hashCodeLo, value);
            } else if (hashCodeLo == getHashCodeLo(offsets, index) && eq(offset = getOffset(rawOffset))) {
                long startAddress = heapStart + offset;
                return valueOf(startAddress, startAddress + keyOffset + keySize, false, value);
            }
            return probe0(this, index, hashCodeLo, keySize, value);
        }

        private MapValue findValue(OrderedMapValue value) {
            long keySize = commit();
            long hashCode = hash();
            int hashCodeLo = Numbers.decodeLowInt(hashCode);
            int index = hashCodeLo & mask;

            int rawOffset = getRawOffset(offsets, index);
            long offset;
            if (rawOffset == 0) {
                return null;
            } else if (hashCodeLo == getHashCodeLo(offsets, index) && eq(offset = getOffset(rawOffset))) {
                long startAddress = heapStart + offset;
                return valueOf(startAddress, startAddress + keyOffset + keySize, false, value);
            } else {
                return probeReadOnly(this, index, hashCodeLo, keySize, value);
            }
        }

        protected void checkCapacity(long requiredKeySize) {
            long requiredSize = requiredKeySize + valueSize;
            if (appendAddress + requiredSize > heapLimit) {
                long delta = resize(requiredSize, appendAddress);
                startAddress += delta;
                appendAddress += delta;
                assert startAddress > 0;
                assert appendAddress > 0;
            }
        }

        abstract void copyFromRawKey(long srcPtr, long srcSize);

        protected abstract boolean eq(long offset);
    }

    class VarSizeKey extends Key {
        private long len;

        @Override
        public long commit() {
            len = appendAddress - startAddress - keyOffset;
            Unsafe.getUnsafe().putInt(startAddress, (int) len);
            return len;
        }

        @Override
        public void copyFrom(MapKey srcKey) {
            VarSizeKey srcFastKey = (VarSizeKey) srcKey;
            copyFromRawKey(srcFastKey.startAddress + keyOffset, srcFastKey.len);
        }

        @Override
        public void copyFromRawKey(long srcPtr, long srcSize) {
            checkCapacity(srcSize);
            Vect.memcpy(appendAddress, srcPtr, srcSize);
            appendAddress += srcSize;
        }

        @Override
        public long hash() {
            return Hash.hashMem64(startAddress + keyOffset, len);
        }

        @Override
        public void putBin(BinarySequence value) {
            if (value == null) {
                putVarSizeNull();
            } else {
                long len = value.length() + 4L;
                if (len > Integer.MAX_VALUE) {
                    throw CairoException.nonCritical().put("binary column is too large");
                }

                checkCapacity((int) len);
                int l = (int) (len - Integer.BYTES);
                Unsafe.getUnsafe().putInt(appendAddress, l);
                value.copyTo(appendAddress + Integer.BYTES, 0, l);
                appendAddress += len;
            }
        }

        @Override
        public void putBool(boolean value) {
            checkCapacity(1L);
            Unsafe.getUnsafe().putByte(appendAddress, (byte) (value ? 1 : 0));
            appendAddress += 1;
        }

        @Override
        public void putByte(byte value) {
            checkCapacity(1L);
            Unsafe.getUnsafe().putByte(appendAddress, value);
            appendAddress += 1L;
        }

        @Override
        public void putChar(char value) {
            checkCapacity(2L);
            Unsafe.getUnsafe().putChar(appendAddress, value);
            appendAddress += 2L;
        }

        @Override
        public void putDate(long value) {
            putLong(value);
        }

        @Override
        public void putDouble(double value) {
            checkCapacity(8L);
            Unsafe.getUnsafe().putDouble(appendAddress, value);
            appendAddress += 8L;
        }

        @Override
        public void putFloat(float value) {
            checkCapacity(4L);
            Unsafe.getUnsafe().putFloat(appendAddress, value);
            appendAddress += 4L;
        }

        @Override
        public void putIPv4(int value) {
            putInt(value);
        }

        @Override
        public void putInt(int value) {
            checkCapacity(4L);
            Unsafe.getUnsafe().putInt(appendAddress, value);
            appendAddress += 4L;
        }

        @Override
        public void putLong(long value) {
            checkCapacity(8L);
            Unsafe.getUnsafe().putLong(appendAddress, value);
            appendAddress += 8L;
        }

        @Override
        public void putLong128(long lo, long hi) {
            checkCapacity(16L);
            Unsafe.getUnsafe().putLong(appendAddress, lo);
            Unsafe.getUnsafe().putLong(appendAddress + Long.BYTES, hi);
            appendAddress += 16L;
        }

        @Override
        public void putLong256(Long256 value) {
            checkCapacity(32L);
            Unsafe.getUnsafe().putLong(appendAddress, value.getLong0());
            Unsafe.getUnsafe().putLong(appendAddress + Long.BYTES, value.getLong1());
            Unsafe.getUnsafe().putLong(appendAddress + Long.BYTES * 2, value.getLong2());
            Unsafe.getUnsafe().putLong(appendAddress + Long.BYTES * 3, value.getLong3());
            appendAddress += 32L;
        }

        @Override
        public void putLong256(long l0, long l1, long l2, long l3) {
            checkCapacity(32L);
            Unsafe.getUnsafe().putLong(appendAddress, l0);
            Unsafe.getUnsafe().putLong(appendAddress + Long.BYTES, l1);
            Unsafe.getUnsafe().putLong(appendAddress + Long.BYTES * 2, l2);
            Unsafe.getUnsafe().putLong(appendAddress + Long.BYTES * 3, l3);
            appendAddress += 32L;
        }

        @Override
        public void putShort(short value) {
            checkCapacity(2L);
            Unsafe.getUnsafe().putShort(appendAddress, value);
            appendAddress += 2L;
        }

        @Override
        public void putStr(CharSequence value) {
            if (value == null) {
                putVarSizeNull();
                return;
            }

            int len = value.length();
            checkCapacity(((long) len << 1) + 4L);
            Unsafe.getUnsafe().putInt(appendAddress, len);
            appendAddress += 4L;
            for (int i = 0; i < len; i++) {
                Unsafe.getUnsafe().putChar(appendAddress + ((long) i << 1), value.charAt(i));
            }
            appendAddress += (long) len << 1;
        }

        @Override
        public void putStr(CharSequence value, int lo, int hi) {
            int len = hi - lo;
            checkCapacity(((long) len << 1) + 4L);
            Unsafe.getUnsafe().putInt(appendAddress, len);
            appendAddress += 4L;
            for (int i = lo; i < hi; i++) {
                Unsafe.getUnsafe().putChar(appendAddress + ((long) (i - lo) << 1), value.charAt(i));
            }
            appendAddress += (long) len << 1;
        }

        @Override
        public void putStrLowerCase(CharSequence value) {
            if (value == null) {
                putVarSizeNull();
                return;
            }

            int len = value.length();
            checkCapacity(((long) len << 1) + 4L);
            Unsafe.getUnsafe().putInt(appendAddress, len);
            appendAddress += 4L;
            for (int i = 0; i < len; i++) {
                Unsafe.getUnsafe().putChar(appendAddress + ((long) i << 1), Character.toLowerCase(value.charAt(i)));
            }
            appendAddress += (long) len << 1;
        }

        @Override
        public void putStrLowerCase(CharSequence value, int lo, int hi) {
            int len = hi - lo;
            checkCapacity(((long) len << 1) + 4L);
            Unsafe.getUnsafe().putInt(appendAddress, len);
            appendAddress += 4L;
            for (int i = lo; i < hi; i++) {
                Unsafe.getUnsafe().putChar(appendAddress + ((long) (i - lo) << 1), Character.toLowerCase(value.charAt(i)));
            }
            appendAddress += (long) len << 1;
        }

        @Override
        public void putTimestamp(long value) {
            putLong(value);
        }

        @Override
        public void putVarchar(Utf8Sequence value) {
            int byteCount = VarcharTypeDriver.getSingleMemValueByteCount(value);
            checkCapacity(byteCount);
            VarcharTypeDriver.appendPlainValue(appendAddress, value, false);
            appendAddress += byteCount;
        }

        @Override
        public void skip(int bytes) {
            checkCapacity(bytes);
            appendAddress += bytes;
        }

        private void putVarSizeNull() {
            checkCapacity(4L);
            Unsafe.getUnsafe().putInt(appendAddress, TableUtils.NULL_LEN);
            appendAddress += 4L;
        }

        @Override
        protected boolean eq(long offset) {
            long a = heapStart + offset;
            long b = startAddress;
            // Check the length first.
            if (Unsafe.getUnsafe().getInt(a) != Unsafe.getUnsafe().getInt(b)) {
                return false;
            }
            return Vect.memeq(a + keyOffset, b + keyOffset, len);
        }
    }
}
