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
#include <jni.h>

#include "converters.h"
#include "simd.h"

// Used to clean up noise in the switch statement
#define macro_dispatch_fixed_to_fixed(a, b, a_ty, b_ty) \
case pack_column_types(a, b): \
        static_assert(is_matching_type<a, a_ty>()); \
        static_assert(is_matching_type<b, b_ty>()); \
        status = convert_fixed_to_fixed_numeric<a_ty, b_ty, is_nullable<a>(), is_nullable<b>()>(reinterpret_cast<a_ty*>(srcMem), reinterpret_cast<b_ty*>(dstMem), \
          get_null_sentinel<a, a_ty>(), get_null_sentinel<b, b_ty>(), static_cast<size_t>(rowCount)); \
break;

void convert_us_to_ns(int64_t *dest, const int64_t *src, const int64_t count) {
    for(int64_t i = 0; i < count; i++) {
        const bool isnull = (src[i] == get_null_sentinel<ColumnType::TIMESTAMP, int64_t>());
        dest[i] = (!isnull) * src[i] / 1000;
        dest[i] += (isnull) * get_null_sentinel<ColumnType::TIMESTAMP, int64_t>();
    }
}

void convert_ns_to_us(int64_t *dest, const int64_t *src, const int64_t count) {
    for(int64_t i = 0; i < count; i++) {
        const bool isnull = (src[i] == get_null_sentinel<ColumnType::TIMESTAMP, int64_t>());
        dest[i] = (!isnull) * src[i] * 1000;
        dest[i] += (isnull) * get_null_sentinel<ColumnType::TIMESTAMP, int64_t>();
    }
}

// extern "C"
extern "C" {
JNIEXPORT jlong
JNICALL
Java_io_questdb_griffin_ConvertersNative_fixedToFixed
(
    JNIEnv */*env*/,
    jclass /*cl*/,
    jlong srcMem,
    jlong srcType,
    jlong dstMem,
    jlong dstType,
    jlong rowCount
) {
    const auto srcColumnType = static_cast<ColumnType>(srcType);
    const auto dstColumnType = static_cast<ColumnType>(dstType);

    ConversionError status;

    switch (pack_column_types(srcColumnType, dstColumnType)) {
        // BOOL
        macro_dispatch_fixed_to_fixed(ColumnType::BOOLEAN, ColumnType::BYTE, bool, int8_t)
        macro_dispatch_fixed_to_fixed(ColumnType::BOOLEAN, ColumnType::SHORT, bool, int16_t)
        macro_dispatch_fixed_to_fixed(ColumnType::BOOLEAN, ColumnType::INT, bool, int32_t)
        macro_dispatch_fixed_to_fixed(ColumnType::BOOLEAN, ColumnType::LONG, bool, int64_t)
        macro_dispatch_fixed_to_fixed(ColumnType::BOOLEAN, ColumnType::FLOAT, bool, float)
        macro_dispatch_fixed_to_fixed(ColumnType::BOOLEAN, ColumnType::DOUBLE, bool, double)
        macro_dispatch_fixed_to_fixed(ColumnType::BOOLEAN, ColumnType::TIMESTAMP, bool, int64_t)
        macro_dispatch_fixed_to_fixed(ColumnType::BOOLEAN, ColumnType::DATE, bool, int64_t)
        // BYTE
        macro_dispatch_fixed_to_fixed(ColumnType::BYTE, ColumnType::SHORT, int8_t, int16_t)
        macro_dispatch_fixed_to_fixed(ColumnType::BYTE, ColumnType::INT, int8_t, int32_t)
        macro_dispatch_fixed_to_fixed(ColumnType::BYTE, ColumnType::LONG, int8_t, int64_t)
        macro_dispatch_fixed_to_fixed(ColumnType::BYTE, ColumnType::FLOAT, int8_t, float)
        macro_dispatch_fixed_to_fixed(ColumnType::BYTE, ColumnType::DOUBLE, int8_t, double)
        macro_dispatch_fixed_to_fixed(ColumnType::BYTE, ColumnType::TIMESTAMP, int8_t, int64_t)
        macro_dispatch_fixed_to_fixed(ColumnType::BYTE, ColumnType::BOOLEAN, int8_t, bool)
        macro_dispatch_fixed_to_fixed(ColumnType::BYTE, ColumnType::DATE, int8_t, int64_t)
        // SHORT
        macro_dispatch_fixed_to_fixed(ColumnType::SHORT, ColumnType::BYTE, int16_t, int8_t)
        macro_dispatch_fixed_to_fixed(ColumnType::SHORT, ColumnType::INT, int16_t, int32_t)
        macro_dispatch_fixed_to_fixed(ColumnType::SHORT, ColumnType::LONG, int16_t, int64_t)
        macro_dispatch_fixed_to_fixed(ColumnType::SHORT, ColumnType::FLOAT, int16_t, float)
        macro_dispatch_fixed_to_fixed(ColumnType::SHORT, ColumnType::DOUBLE, int16_t, double)
        macro_dispatch_fixed_to_fixed(ColumnType::SHORT, ColumnType::TIMESTAMP, int16_t, int64_t)
        macro_dispatch_fixed_to_fixed(ColumnType::SHORT, ColumnType::BOOLEAN, int16_t, bool)
        macro_dispatch_fixed_to_fixed(ColumnType::SHORT, ColumnType::DATE, int16_t, int64_t)
        // INT
        macro_dispatch_fixed_to_fixed(ColumnType::INT, ColumnType::BYTE, int32_t, int8_t)
        macro_dispatch_fixed_to_fixed(ColumnType::INT, ColumnType::SHORT, int32_t, int16_t)
        macro_dispatch_fixed_to_fixed(ColumnType::INT, ColumnType::LONG, int32_t, int64_t)
        macro_dispatch_fixed_to_fixed(ColumnType::INT, ColumnType::FLOAT, int32_t, float)
        macro_dispatch_fixed_to_fixed(ColumnType::INT, ColumnType::DOUBLE, int32_t, double)
        macro_dispatch_fixed_to_fixed(ColumnType::INT, ColumnType::TIMESTAMP, int32_t, int64_t)
        macro_dispatch_fixed_to_fixed(ColumnType::INT, ColumnType::BOOLEAN, int32_t, bool)
        macro_dispatch_fixed_to_fixed(ColumnType::INT, ColumnType::DATE, int32_t, int64_t)
        // LONG
        macro_dispatch_fixed_to_fixed(ColumnType::LONG, ColumnType::BYTE, int64_t, int8_t)
        macro_dispatch_fixed_to_fixed(ColumnType::LONG, ColumnType::SHORT, int64_t, int16_t)
        macro_dispatch_fixed_to_fixed(ColumnType::LONG, ColumnType::INT, int64_t, int32_t)
        macro_dispatch_fixed_to_fixed(ColumnType::LONG, ColumnType::FLOAT, int64_t, float)
        macro_dispatch_fixed_to_fixed(ColumnType::LONG, ColumnType::DOUBLE, int64_t, double)
        macro_dispatch_fixed_to_fixed(ColumnType::LONG, ColumnType::BOOLEAN, int64_t, bool)
        // FLOAT
        macro_dispatch_fixed_to_fixed(ColumnType::FLOAT, ColumnType::BYTE, float, int8_t)
        macro_dispatch_fixed_to_fixed(ColumnType::FLOAT, ColumnType::SHORT, float, int16_t)
        macro_dispatch_fixed_to_fixed(ColumnType::FLOAT, ColumnType::INT, float, int32_t)
        macro_dispatch_fixed_to_fixed(ColumnType::FLOAT, ColumnType::LONG, float, int64_t)
        macro_dispatch_fixed_to_fixed(ColumnType::FLOAT, ColumnType::DOUBLE, float, double)
        macro_dispatch_fixed_to_fixed(ColumnType::FLOAT, ColumnType::TIMESTAMP, float, int64_t)
        macro_dispatch_fixed_to_fixed(ColumnType::FLOAT, ColumnType::BOOLEAN, float, bool)
        macro_dispatch_fixed_to_fixed(ColumnType::FLOAT, ColumnType::DATE, float, int64_t)
        // DOUBLE
        macro_dispatch_fixed_to_fixed(ColumnType::DOUBLE, ColumnType::BYTE, double, int8_t)
        macro_dispatch_fixed_to_fixed(ColumnType::DOUBLE, ColumnType::SHORT, double, int16_t)
        macro_dispatch_fixed_to_fixed(ColumnType::DOUBLE, ColumnType::INT, double, int32_t)
        macro_dispatch_fixed_to_fixed(ColumnType::DOUBLE, ColumnType::LONG, double, int64_t)
        macro_dispatch_fixed_to_fixed(ColumnType::DOUBLE, ColumnType::FLOAT, double, float)
        macro_dispatch_fixed_to_fixed(ColumnType::DOUBLE, ColumnType::TIMESTAMP, double, int64_t)
        macro_dispatch_fixed_to_fixed(ColumnType::DOUBLE, ColumnType::BOOLEAN, double, bool)
        macro_dispatch_fixed_to_fixed(ColumnType::DOUBLE, ColumnType::DATE, double, int64_t)
        // TIMESTAMP
        macro_dispatch_fixed_to_fixed(ColumnType::TIMESTAMP, ColumnType::BYTE, int64_t, int8_t)
        macro_dispatch_fixed_to_fixed(ColumnType::TIMESTAMP, ColumnType::SHORT, int64_t, int16_t)
        macro_dispatch_fixed_to_fixed(ColumnType::TIMESTAMP, ColumnType::INT, int64_t, int32_t)
        macro_dispatch_fixed_to_fixed(ColumnType::TIMESTAMP, ColumnType::FLOAT, int64_t, float)
        macro_dispatch_fixed_to_fixed(ColumnType::TIMESTAMP, ColumnType::DOUBLE, int64_t, double)
        macro_dispatch_fixed_to_fixed(ColumnType::TIMESTAMP, ColumnType::BOOLEAN, int64_t, bool)
        // DATE
        macro_dispatch_fixed_to_fixed(ColumnType::DATE, ColumnType::BYTE, int64_t, int8_t)
        macro_dispatch_fixed_to_fixed(ColumnType::DATE, ColumnType::SHORT, int64_t, int16_t)
        macro_dispatch_fixed_to_fixed(ColumnType::DATE, ColumnType::INT, int64_t, int32_t)
        macro_dispatch_fixed_to_fixed(ColumnType::DATE, ColumnType::FLOAT, int64_t, float)
        macro_dispatch_fixed_to_fixed(ColumnType::DATE, ColumnType::DOUBLE, int64_t, double)
        macro_dispatch_fixed_to_fixed(ColumnType::DATE, ColumnType::BOOLEAN, int64_t, bool)

        case pack_column_types(ColumnType::TIMESTAMP, ColumnType::LONG):
        case pack_column_types(ColumnType::LONG, ColumnType::TIMESTAMP):
        case pack_column_types(ColumnType::LONG, ColumnType::DATE):
        case pack_column_types(ColumnType::DATE, ColumnType::LONG):
            __MEMCPY(reinterpret_cast<int64_t *>(dstMem), reinterpret_cast<int64_t *>(srcMem), rowCount * sizeof(int64_t));
            break;
        case pack_column_types(ColumnType::TIMESTAMP, ColumnType::DATE):
            convert_us_to_ns(reinterpret_cast<int64_t *>(dstMem), reinterpret_cast<int64_t *>(srcMem), rowCount);
            break;
        case pack_column_types(ColumnType::DATE, ColumnType::TIMESTAMP):
            convert_ns_to_us(reinterpret_cast<int64_t *>(dstMem), reinterpret_cast<int64_t *>(srcMem), rowCount);
            break;
        default:
            status = ConversionError::UNSUPPORTED_CAST;
    }

    return static_cast<jlong>(status);
}
}
