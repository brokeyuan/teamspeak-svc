//--------------------------------------------------------------------------
// Ported from SVC-Extra (https://github.com/liuyuexiaoyu1/SVC-Extra), GPLv3.
// Which incorporates code from Channel (https://github.com/USS-Shenzhou/Channel).
//--------------------------------------------------------------------------
/* Channel mod by USS_Shenzhou -- GPL-3.0 */
package com.tsvoice.client.audio.nativ;
import java.lang.invoke.*;
import java.lang.foreign.*;
import static java.lang.foreign.ValueLayout.*;
public class NvAudioEffects extends CShared {
    NvAudioEffects() {
    }
    static final Arena LIBRARY_ARENA = Arena.ofAuto();
    static final SymbolLookup SYMBOL_LOOKUP = SymbolLookup.libraryLookup(System.mapLibraryName("nvAudioEffects"), LIBRARY_ARENA)
            .or(SymbolLookup.loaderLookup())
            .or(Linker.nativeLinker().defaultLookup());
    private static final int true_ = (int)1L;
    public static int true_() {
        return true_;
    }
    private static final int false_ = (int)0L;
    public static int false_() {
        return false_;
    }
    private static final int __bool_true_false_are_defined = (int)1L;
    public static int __bool_true_false_are_defined() {
        return __bool_true_false_are_defined;
    }
    private static final int _VCRT_COMPILER_PREPROCESSOR = (int)1L;
    public static int _VCRT_COMPILER_PREPROCESSOR() {
        return _VCRT_COMPILER_PREPROCESSOR;
    }
    private static final int _SAL_VERSION = (int)20L;
    public static int _SAL_VERSION() {
        return _SAL_VERSION;
    }
    private static final int __SAL_H_VERSION = (int)180000000L;
    public static int __SAL_H_VERSION() {
        return __SAL_H_VERSION;
    }
    private static final int _USE_DECLSPECS_FOR_SAL = (int)0L;
    public static int _USE_DECLSPECS_FOR_SAL() {
        return _USE_DECLSPECS_FOR_SAL;
    }
    private static final int _USE_ATTRIBUTES_FOR_SAL = (int)0L;
    public static int _USE_ATTRIBUTES_FOR_SAL() {
        return _USE_ATTRIBUTES_FOR_SAL;
    }
    private static final int _CRT_PACKING = (int)8L;
    public static int _CRT_PACKING() {
        return _CRT_PACKING;
    }
    private static final int _HAS_EXCEPTIONS = (int)1L;
    public static int _HAS_EXCEPTIONS() {
        return _HAS_EXCEPTIONS;
    }
    private static final int _HAS_CXX17 = (int)0L;
    public static int _HAS_CXX17() {
        return _HAS_CXX17;
    }
    private static final int _HAS_CXX20 = (int)0L;
    public static int _HAS_CXX20() {
        return _HAS_CXX20;
    }
    private static final int _HAS_NODISCARD = (int)0L;
    public static int _HAS_NODISCARD() {
        return _HAS_NODISCARD;
    }
    private static final int WCHAR_MIN = (int)0L;
    public static int WCHAR_MIN() {
        return WCHAR_MIN;
    }
    private static final int WCHAR_MAX = (int)65535L;
    public static int WCHAR_MAX() {
        return WCHAR_MAX;
    }
    private static final int WINT_MIN = (int)0L;
    public static int WINT_MIN() {
        return WINT_MIN;
    }
    private static final int WINT_MAX = (int)65535L;
    public static int WINT_MAX() {
        return WINT_MAX;
    }
    private static final int NVAFX_TRUE = (int)1L;
    public static int NVAFX_TRUE() {
        return NVAFX_TRUE;
    }
    private static final int NVAFX_FALSE = (int)0L;
    public static int NVAFX_FALSE() {
        return NVAFX_FALSE;
    }
    public static final OfLong ptrdiff_t = NvAudioEffects.C_LONG_LONG;
    public static final OfLong size_t = NvAudioEffects.C_LONG_LONG;
    public static final OfShort wchar_t = NvAudioEffects.C_SHORT;
    public static final OfDouble max_align_t = NvAudioEffects.C_DOUBLE;
    public static final OfLong uintptr_t = NvAudioEffects.C_LONG_LONG;
    public static final AddressLayout va_list = NvAudioEffects.C_POINTER;
    public static class __va_start {
        private static final FunctionDescriptor BASE_DESC = FunctionDescriptor.ofVoid(
                NvAudioEffects.C_POINTER
        );
        private static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("__va_start");
        private final MethodHandle handle;
        private final FunctionDescriptor descriptor;
        private final MethodHandle spreader;
        private __va_start(MethodHandle handle, FunctionDescriptor descriptor, MethodHandle spreader) {
            this.handle = handle;
            this.descriptor = descriptor;
            this.spreader = spreader;
        }
        public static __va_start makeInvoker(MemoryLayout... layouts) {
            FunctionDescriptor desc$ = BASE_DESC.appendArgumentLayouts(layouts);
            Linker.Option fva$ = Linker.Option.firstVariadicArg(BASE_DESC.argumentLayouts().size());
            var mh$ = Linker.nativeLinker().downcallHandle(ADDR, desc$, fva$);
            var spreader$ = mh$.asSpreader(Object[].class, layouts.length);
            return new __va_start(mh$, desc$, spreader$);
        }
        public static MemorySegment address() {
            return ADDR;
        }
        public MethodHandle handle() {
            return handle;
        }
        public FunctionDescriptor descriptor() {
            return descriptor;
        }
        public void apply(MemorySegment x0, Object... x1) {
            try {
                if (TRACE_DOWNCALLS) {
                    traceDowncall("__va_start", x0, x1);
                }
                spreader.invokeExact(x0, x1);
            } catch(IllegalArgumentException | ClassCastException ex$)  {
                throw ex$; 
            } catch (Throwable ex$) {
                throw new AssertionError("should not reach here", ex$);
            }
        }
    }
    public static final OfLong intptr_t = NvAudioEffects.C_LONG_LONG;
    public static final OfBoolean __vcrt_bool = NvAudioEffects.C_BOOL;
    private static class __security_init_cookie {
        public static final FunctionDescriptor DESC = FunctionDescriptor.ofVoid(    );
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("__security_init_cookie");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }
    public static FunctionDescriptor __security_init_cookie$descriptor() {
        return __security_init_cookie.DESC;
    }
    public static MethodHandle __security_init_cookie$handle() {
        return __security_init_cookie.HANDLE;
    }
    public static MemorySegment __security_init_cookie$address() {
        return __security_init_cookie.ADDR;
    }
    public static void __security_init_cookie() {
        var mh$ = __security_init_cookie.HANDLE;
        try {
            if (TRACE_DOWNCALLS) {
                traceDowncall("__security_init_cookie");
            }
            mh$.invokeExact();
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }
    private static class __security_check_cookie {
        public static final FunctionDescriptor DESC = FunctionDescriptor.ofVoid(
                NvAudioEffects.C_LONG_LONG
        );
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("__security_check_cookie");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }
    public static FunctionDescriptor __security_check_cookie$descriptor() {
        return __security_check_cookie.DESC;
    }
    public static MethodHandle __security_check_cookie$handle() {
        return __security_check_cookie.HANDLE;
    }
    public static MemorySegment __security_check_cookie$address() {
        return __security_check_cookie.ADDR;
    }
    public static void __security_check_cookie(long _StackCookie) {
        var mh$ = __security_check_cookie.HANDLE;
        try {
            if (TRACE_DOWNCALLS) {
                traceDowncall("__security_check_cookie", _StackCookie);
            }
            mh$.invokeExact(_StackCookie);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }
    private static class __report_gsfailure {
        public static final FunctionDescriptor DESC = FunctionDescriptor.ofVoid(
                NvAudioEffects.C_LONG_LONG
        );
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("__report_gsfailure");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }
    public static FunctionDescriptor __report_gsfailure$descriptor() {
        return __report_gsfailure.DESC;
    }
    public static MethodHandle __report_gsfailure$handle() {
        return __report_gsfailure.HANDLE;
    }
    public static MemorySegment __report_gsfailure$address() {
        return __report_gsfailure.ADDR;
    }
    public static void __report_gsfailure(long _StackCookie) {
        var mh$ = __report_gsfailure.HANDLE;
        try {
            if (TRACE_DOWNCALLS) {
                traceDowncall("__report_gsfailure", _StackCookie);
            }
            mh$.invokeExact(_StackCookie);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }
    private static class __security_cookie$constants {
        public static final OfLong LAYOUT = NvAudioEffects.C_LONG_LONG;
        public static final MemorySegment SEGMENT = SYMBOL_LOOKUP.findOrThrow("__security_cookie").reinterpret(LAYOUT.byteSize());
    }
    public static OfLong __security_cookie$layout() {
        return __security_cookie$constants.LAYOUT;
    }
    public static MemorySegment __security_cookie$segment() {
        return __security_cookie$constants.SEGMENT;
    }
    public static long __security_cookie() {
        return __security_cookie$constants.SEGMENT.get(__security_cookie$constants.LAYOUT, 0L);
    }
    public static void __security_cookie(long varValue) {
        __security_cookie$constants.SEGMENT.set(__security_cookie$constants.LAYOUT, 0L, varValue);
    }
    public static final OfByte int8_t = NvAudioEffects.C_CHAR;
    public static final OfShort int16_t = NvAudioEffects.C_SHORT;
    public static final OfInt int32_t = NvAudioEffects.C_INT;
    public static final OfLong int64_t = NvAudioEffects.C_LONG_LONG;
    public static final OfByte uint8_t = NvAudioEffects.C_CHAR;
    public static final OfShort uint16_t = NvAudioEffects.C_SHORT;
    public static final OfInt uint32_t = NvAudioEffects.C_INT;
    public static final OfLong uint64_t = NvAudioEffects.C_LONG_LONG;
    public static final OfByte int_least8_t = NvAudioEffects.C_CHAR;
    public static final OfShort int_least16_t = NvAudioEffects.C_SHORT;
    public static final OfInt int_least32_t = NvAudioEffects.C_INT;
    public static final OfLong int_least64_t = NvAudioEffects.C_LONG_LONG;
    public static final OfByte uint_least8_t = NvAudioEffects.C_CHAR;
    public static final OfShort uint_least16_t = NvAudioEffects.C_SHORT;
    public static final OfInt uint_least32_t = NvAudioEffects.C_INT;
    public static final OfLong uint_least64_t = NvAudioEffects.C_LONG_LONG;
    public static final OfByte int_fast8_t = NvAudioEffects.C_CHAR;
    public static final OfInt int_fast16_t = NvAudioEffects.C_INT;
    public static final OfInt int_fast32_t = NvAudioEffects.C_INT;
    public static final OfLong int_fast64_t = NvAudioEffects.C_LONG_LONG;
    public static final OfByte uint_fast8_t = NvAudioEffects.C_CHAR;
    public static final OfInt uint_fast16_t = NvAudioEffects.C_INT;
    public static final OfInt uint_fast32_t = NvAudioEffects.C_INT;
    public static final OfLong uint_fast64_t = NvAudioEffects.C_LONG_LONG;
    public static final OfLong intmax_t = NvAudioEffects.C_LONG_LONG;
    public static final OfLong uintmax_t = NvAudioEffects.C_LONG_LONG;
    private static final int NVAFX_STATUS_SUCCESS = (int)0L;
    public static int NVAFX_STATUS_SUCCESS() {
        return NVAFX_STATUS_SUCCESS;
    }
    private static final int NVAFX_STATUS_FAILED = (int)1L;
    public static int NVAFX_STATUS_FAILED() {
        return NVAFX_STATUS_FAILED;
    }
    private static final int NVAFX_STATUS_INVALID_HANDLE = (int)2L;
    public static int NVAFX_STATUS_INVALID_HANDLE() {
        return NVAFX_STATUS_INVALID_HANDLE;
    }
    private static final int NVAFX_STATUS_INVALID_PARAM = (int)3L;
    public static int NVAFX_STATUS_INVALID_PARAM() {
        return NVAFX_STATUS_INVALID_PARAM;
    }
    private static final int NVAFX_STATUS_IMMUTABLE_PARAM = (int)4L;
    public static int NVAFX_STATUS_IMMUTABLE_PARAM() {
        return NVAFX_STATUS_IMMUTABLE_PARAM;
    }
    private static final int NVAFX_STATUS_INSUFFICIENT_DATA = (int)5L;
    public static int NVAFX_STATUS_INSUFFICIENT_DATA() {
        return NVAFX_STATUS_INSUFFICIENT_DATA;
    }
    private static final int NVAFX_STATUS_EFFECT_NOT_AVAILABLE = (int)6L;
    public static int NVAFX_STATUS_EFFECT_NOT_AVAILABLE() {
        return NVAFX_STATUS_EFFECT_NOT_AVAILABLE;
    }
    private static final int NVAFX_STATUS_OUTPUT_BUFFER_TOO_SMALL = (int)7L;
    public static int NVAFX_STATUS_OUTPUT_BUFFER_TOO_SMALL() {
        return NVAFX_STATUS_OUTPUT_BUFFER_TOO_SMALL;
    }
    private static final int NVAFX_STATUS_MODEL_LOAD_FAILED = (int)8L;
    public static int NVAFX_STATUS_MODEL_LOAD_FAILED() {
        return NVAFX_STATUS_MODEL_LOAD_FAILED;
    }
    private static final int NVAFX_STATUS_32_SERVER_NOT_REGISTERED = (int)9L;
    public static int NVAFX_STATUS_32_SERVER_NOT_REGISTERED() {
        return NVAFX_STATUS_32_SERVER_NOT_REGISTERED;
    }
    private static final int NVAFX_STATUS_32_COM_ERROR = (int)10L;
    public static int NVAFX_STATUS_32_COM_ERROR() {
        return NVAFX_STATUS_32_COM_ERROR;
    }
    private static final int NVAFX_STATUS_GPU_UNSUPPORTED = (int)11L;
    public static int NVAFX_STATUS_GPU_UNSUPPORTED() {
        return NVAFX_STATUS_GPU_UNSUPPORTED;
    }
    private static final int NVAFX_STATUS_CUDA_CONTEXT_CREATION_FAILED = (int)12L;
    public static int NVAFX_STATUS_CUDA_CONTEXT_CREATION_FAILED() {
        return NVAFX_STATUS_CUDA_CONTEXT_CREATION_FAILED;
    }
    public static final OfByte NvAFX_Bool = NvAudioEffects.C_CHAR;
    public static final AddressLayout NvAFX_EffectSelector = NvAudioEffects.C_POINTER;
    public static final AddressLayout NvAFX_ParameterSelector = NvAudioEffects.C_POINTER;
    public static final AddressLayout NvAFX_Handle = NvAudioEffects.C_POINTER;
    private static class NvAFX_GetEffectList {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
                NvAudioEffects.C_INT,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER
        );
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("NvAFX_GetEffectList");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }
    public static FunctionDescriptor NvAFX_GetEffectList$descriptor() {
        return NvAFX_GetEffectList.DESC;
    }
    public static MethodHandle NvAFX_GetEffectList$handle() {
        return NvAFX_GetEffectList.HANDLE;
    }
    public static MemorySegment NvAFX_GetEffectList$address() {
        return NvAFX_GetEffectList.ADDR;
    }
    public static int NvAFX_GetEffectList(MemorySegment num_effects, MemorySegment effects) {
        var mh$ = NvAFX_GetEffectList.HANDLE;
        try {
            if (TRACE_DOWNCALLS) {
                traceDowncall("NvAFX_GetEffectList", num_effects, effects);
            }
            return (int)mh$.invokeExact(num_effects, effects);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }
    private static class NvAFX_CreateEffect {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
                NvAudioEffects.C_INT,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER
        );
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("NvAFX_CreateEffect");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }
    public static FunctionDescriptor NvAFX_CreateEffect$descriptor() {
        return NvAFX_CreateEffect.DESC;
    }
    public static MethodHandle NvAFX_CreateEffect$handle() {
        return NvAFX_CreateEffect.HANDLE;
    }
    public static MemorySegment NvAFX_CreateEffect$address() {
        return NvAFX_CreateEffect.ADDR;
    }
    public static int NvAFX_CreateEffect(MemorySegment code, MemorySegment effect) {
        var mh$ = NvAFX_CreateEffect.HANDLE;
        try {
            if (TRACE_DOWNCALLS) {
                traceDowncall("NvAFX_CreateEffect", code, effect);
            }
            return (int)mh$.invokeExact(code, effect);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }
    private static class NvAFX_CreateChainedEffect {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
                NvAudioEffects.C_INT,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER
        );
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("NvAFX_CreateChainedEffect");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }
    public static FunctionDescriptor NvAFX_CreateChainedEffect$descriptor() {
        return NvAFX_CreateChainedEffect.DESC;
    }
    public static MethodHandle NvAFX_CreateChainedEffect$handle() {
        return NvAFX_CreateChainedEffect.HANDLE;
    }
    public static MemorySegment NvAFX_CreateChainedEffect$address() {
        return NvAFX_CreateChainedEffect.ADDR;
    }
    public static int NvAFX_CreateChainedEffect(MemorySegment code, MemorySegment effect) {
        var mh$ = NvAFX_CreateChainedEffect.HANDLE;
        try {
            if (TRACE_DOWNCALLS) {
                traceDowncall("NvAFX_CreateChainedEffect", code, effect);
            }
            return (int)mh$.invokeExact(code, effect);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }
    private static class NvAFX_DestroyEffect {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
                NvAudioEffects.C_INT,
                NvAudioEffects.C_POINTER
        );
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("NvAFX_DestroyEffect");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }
    public static FunctionDescriptor NvAFX_DestroyEffect$descriptor() {
        return NvAFX_DestroyEffect.DESC;
    }
    public static MethodHandle NvAFX_DestroyEffect$handle() {
        return NvAFX_DestroyEffect.HANDLE;
    }
    public static MemorySegment NvAFX_DestroyEffect$address() {
        return NvAFX_DestroyEffect.ADDR;
    }
    public static int NvAFX_DestroyEffect(MemorySegment effect) {
        var mh$ = NvAFX_DestroyEffect.HANDLE;
        try {
            if (TRACE_DOWNCALLS) {
                traceDowncall("NvAFX_DestroyEffect", effect);
            }
            return (int)mh$.invokeExact(effect);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }
    private static class NvAFX_SetU32 {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
                NvAudioEffects.C_INT,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_INT
        );
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("NvAFX_SetU32");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }
    public static FunctionDescriptor NvAFX_SetU32$descriptor() {
        return NvAFX_SetU32.DESC;
    }
    public static MethodHandle NvAFX_SetU32$handle() {
        return NvAFX_SetU32.HANDLE;
    }
    public static MemorySegment NvAFX_SetU32$address() {
        return NvAFX_SetU32.ADDR;
    }
    public static int NvAFX_SetU32(MemorySegment effect, MemorySegment param_name, int val) {
        var mh$ = NvAFX_SetU32.HANDLE;
        try {
            if (TRACE_DOWNCALLS) {
                traceDowncall("NvAFX_SetU32", effect, param_name, val);
            }
            return (int)mh$.invokeExact(effect, param_name, val);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }
    private static class NvAFX_SetU32List {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
                NvAudioEffects.C_INT,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_INT
        );
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("NvAFX_SetU32List");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }
    public static FunctionDescriptor NvAFX_SetU32List$descriptor() {
        return NvAFX_SetU32List.DESC;
    }
    public static MethodHandle NvAFX_SetU32List$handle() {
        return NvAFX_SetU32List.HANDLE;
    }
    public static MemorySegment NvAFX_SetU32List$address() {
        return NvAFX_SetU32List.ADDR;
    }
    public static int NvAFX_SetU32List(MemorySegment effect, MemorySegment param_name, MemorySegment val, int size) {
        var mh$ = NvAFX_SetU32List.HANDLE;
        try {
            if (TRACE_DOWNCALLS) {
                traceDowncall("NvAFX_SetU32List", effect, param_name, val, size);
            }
            return (int)mh$.invokeExact(effect, param_name, val, size);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }
    private static class NvAFX_SetString {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
                NvAudioEffects.C_INT,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER
        );
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("NvAFX_SetString");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }
    public static FunctionDescriptor NvAFX_SetString$descriptor() {
        return NvAFX_SetString.DESC;
    }
    public static MethodHandle NvAFX_SetString$handle() {
        return NvAFX_SetString.HANDLE;
    }
    public static MemorySegment NvAFX_SetString$address() {
        return NvAFX_SetString.ADDR;
    }
    public static int NvAFX_SetString(MemorySegment effect, MemorySegment param_name, MemorySegment val) {
        var mh$ = NvAFX_SetString.HANDLE;
        try {
            if (TRACE_DOWNCALLS) {
                traceDowncall("NvAFX_SetString", effect, param_name, val);
            }
            return (int)mh$.invokeExact(effect, param_name, val);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }
    private static class NvAFX_SetStringList {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
                NvAudioEffects.C_INT,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_INT
        );
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("NvAFX_SetStringList");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }
    public static FunctionDescriptor NvAFX_SetStringList$descriptor() {
        return NvAFX_SetStringList.DESC;
    }
    public static MethodHandle NvAFX_SetStringList$handle() {
        return NvAFX_SetStringList.HANDLE;
    }
    public static MemorySegment NvAFX_SetStringList$address() {
        return NvAFX_SetStringList.ADDR;
    }
    public static int NvAFX_SetStringList(MemorySegment effect, MemorySegment param_name, MemorySegment val, int size) {
        var mh$ = NvAFX_SetStringList.HANDLE;
        try {
            if (TRACE_DOWNCALLS) {
                traceDowncall("NvAFX_SetStringList", effect, param_name, val, size);
            }
            return (int)mh$.invokeExact(effect, param_name, val, size);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }
    private static class NvAFX_SetFloat {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
                NvAudioEffects.C_INT,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_FLOAT
        );
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("NvAFX_SetFloat");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }
    public static FunctionDescriptor NvAFX_SetFloat$descriptor() {
        return NvAFX_SetFloat.DESC;
    }
    public static MethodHandle NvAFX_SetFloat$handle() {
        return NvAFX_SetFloat.HANDLE;
    }
    public static MemorySegment NvAFX_SetFloat$address() {
        return NvAFX_SetFloat.ADDR;
    }
    public static int NvAFX_SetFloat(MemorySegment effect, MemorySegment param_name, float val) {
        var mh$ = NvAFX_SetFloat.HANDLE;
        try {
            if (TRACE_DOWNCALLS) {
                traceDowncall("NvAFX_SetFloat", effect, param_name, val);
            }
            return (int)mh$.invokeExact(effect, param_name, val);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }
    private static class NvAFX_SetFloatList {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
                NvAudioEffects.C_INT,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_INT
        );
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("NvAFX_SetFloatList");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }
    public static FunctionDescriptor NvAFX_SetFloatList$descriptor() {
        return NvAFX_SetFloatList.DESC;
    }
    public static MethodHandle NvAFX_SetFloatList$handle() {
        return NvAFX_SetFloatList.HANDLE;
    }
    public static MemorySegment NvAFX_SetFloatList$address() {
        return NvAFX_SetFloatList.ADDR;
    }
    public static int NvAFX_SetFloatList(MemorySegment effect, MemorySegment param_name, MemorySegment val, int size) {
        var mh$ = NvAFX_SetFloatList.HANDLE;
        try {
            if (TRACE_DOWNCALLS) {
                traceDowncall("NvAFX_SetFloatList", effect, param_name, val, size);
            }
            return (int)mh$.invokeExact(effect, param_name, val, size);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }
    private static class NvAFX_GetU32 {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
                NvAudioEffects.C_INT,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER
        );
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("NvAFX_GetU32");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }
    public static FunctionDescriptor NvAFX_GetU32$descriptor() {
        return NvAFX_GetU32.DESC;
    }
    public static MethodHandle NvAFX_GetU32$handle() {
        return NvAFX_GetU32.HANDLE;
    }
    public static MemorySegment NvAFX_GetU32$address() {
        return NvAFX_GetU32.ADDR;
    }
    public static int NvAFX_GetU32(MemorySegment effect, MemorySegment param_name, MemorySegment val) {
        var mh$ = NvAFX_GetU32.HANDLE;
        try {
            if (TRACE_DOWNCALLS) {
                traceDowncall("NvAFX_GetU32", effect, param_name, val);
            }
            return (int)mh$.invokeExact(effect, param_name, val);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }
    private static class NvAFX_GetString {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
                NvAudioEffects.C_INT,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_INT
        );
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("NvAFX_GetString");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }
    public static FunctionDescriptor NvAFX_GetString$descriptor() {
        return NvAFX_GetString.DESC;
    }
    public static MethodHandle NvAFX_GetString$handle() {
        return NvAFX_GetString.HANDLE;
    }
    public static MemorySegment NvAFX_GetString$address() {
        return NvAFX_GetString.ADDR;
    }
    public static int NvAFX_GetString(MemorySegment effect, MemorySegment param_name, MemorySegment val, int max_length) {
        var mh$ = NvAFX_GetString.HANDLE;
        try {
            if (TRACE_DOWNCALLS) {
                traceDowncall("NvAFX_GetString", effect, param_name, val, max_length);
            }
            return (int)mh$.invokeExact(effect, param_name, val, max_length);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }
    private static class NvAFX_GetStringList {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
                NvAudioEffects.C_INT,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_INT
        );
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("NvAFX_GetStringList");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }
    public static FunctionDescriptor NvAFX_GetStringList$descriptor() {
        return NvAFX_GetStringList.DESC;
    }
    public static MethodHandle NvAFX_GetStringList$handle() {
        return NvAFX_GetStringList.HANDLE;
    }
    public static MemorySegment NvAFX_GetStringList$address() {
        return NvAFX_GetStringList.ADDR;
    }
    public static int NvAFX_GetStringList(MemorySegment effect, MemorySegment param_name, MemorySegment val, MemorySegment max_length, int size) {
        var mh$ = NvAFX_GetStringList.HANDLE;
        try {
            if (TRACE_DOWNCALLS) {
                traceDowncall("NvAFX_GetStringList", effect, param_name, val, max_length, size);
            }
            return (int)mh$.invokeExact(effect, param_name, val, max_length, size);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }
    private static class NvAFX_GetFloat {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
                NvAudioEffects.C_INT,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER
        );
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("NvAFX_GetFloat");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }
    public static FunctionDescriptor NvAFX_GetFloat$descriptor() {
        return NvAFX_GetFloat.DESC;
    }
    public static MethodHandle NvAFX_GetFloat$handle() {
        return NvAFX_GetFloat.HANDLE;
    }
    public static MemorySegment NvAFX_GetFloat$address() {
        return NvAFX_GetFloat.ADDR;
    }
    public static int NvAFX_GetFloat(MemorySegment effect, MemorySegment param_name, MemorySegment val) {
        var mh$ = NvAFX_GetFloat.HANDLE;
        try {
            if (TRACE_DOWNCALLS) {
                traceDowncall("NvAFX_GetFloat", effect, param_name, val);
            }
            return (int)mh$.invokeExact(effect, param_name, val);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }
    private static class NvAFX_GetFloatList {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
                NvAudioEffects.C_INT,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_INT
        );
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("NvAFX_GetFloatList");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }
    public static FunctionDescriptor NvAFX_GetFloatList$descriptor() {
        return NvAFX_GetFloatList.DESC;
    }
    public static MethodHandle NvAFX_GetFloatList$handle() {
        return NvAFX_GetFloatList.HANDLE;
    }
    public static MemorySegment NvAFX_GetFloatList$address() {
        return NvAFX_GetFloatList.ADDR;
    }
    public static int NvAFX_GetFloatList(MemorySegment effect, MemorySegment param_name, MemorySegment val, int size) {
        var mh$ = NvAFX_GetFloatList.HANDLE;
        try {
            if (TRACE_DOWNCALLS) {
                traceDowncall("NvAFX_GetFloatList", effect, param_name, val, size);
            }
            return (int)mh$.invokeExact(effect, param_name, val, size);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }
    private static class NvAFX_Load {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
                NvAudioEffects.C_INT,
                NvAudioEffects.C_POINTER
        );
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("NvAFX_Load");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }
    public static FunctionDescriptor NvAFX_Load$descriptor() {
        return NvAFX_Load.DESC;
    }
    public static MethodHandle NvAFX_Load$handle() {
        return NvAFX_Load.HANDLE;
    }
    public static MemorySegment NvAFX_Load$address() {
        return NvAFX_Load.ADDR;
    }
    public static int NvAFX_Load(MemorySegment effect) {
        var mh$ = NvAFX_Load.HANDLE;
        try {
            if (TRACE_DOWNCALLS) {
                traceDowncall("NvAFX_Load", effect);
            }
            return (int)mh$.invokeExact(effect);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }
    private static class NvAFX_GetSupportedDevices {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
                NvAudioEffects.C_INT,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER
        );
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("NvAFX_GetSupportedDevices");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }
    public static FunctionDescriptor NvAFX_GetSupportedDevices$descriptor() {
        return NvAFX_GetSupportedDevices.DESC;
    }
    public static MethodHandle NvAFX_GetSupportedDevices$handle() {
        return NvAFX_GetSupportedDevices.HANDLE;
    }
    public static MemorySegment NvAFX_GetSupportedDevices$address() {
        return NvAFX_GetSupportedDevices.ADDR;
    }
    public static int NvAFX_GetSupportedDevices(MemorySegment effect, MemorySegment num, MemorySegment devices) {
        var mh$ = NvAFX_GetSupportedDevices.HANDLE;
        try {
            if (TRACE_DOWNCALLS) {
                traceDowncall("NvAFX_GetSupportedDevices", effect, num, devices);
            }
            return (int)mh$.invokeExact(effect, num, devices);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }
    private static class NvAFX_Run {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
                NvAudioEffects.C_INT,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_POINTER,
                NvAudioEffects.C_INT,
                NvAudioEffects.C_INT
        );
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("NvAFX_Run");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }
    public static FunctionDescriptor NvAFX_Run$descriptor() {
        return NvAFX_Run.DESC;
    }
    public static MethodHandle NvAFX_Run$handle() {
        return NvAFX_Run.HANDLE;
    }
    public static MemorySegment NvAFX_Run$address() {
        return NvAFX_Run.ADDR;
    }
    public static int NvAFX_Run(MemorySegment effect, MemorySegment input, MemorySegment output, int num_input_samples, int num_input_channels) {
        var mh$ = NvAFX_Run.HANDLE;
        try {
            if (TRACE_DOWNCALLS) {
                traceDowncall("NvAFX_Run", effect, input, output, num_input_samples, num_input_channels);
            }
            return (int)mh$.invokeExact(effect, input, output, num_input_samples, num_input_channels);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }
    private static class NvAFX_Reset {
        public static final FunctionDescriptor DESC = FunctionDescriptor.of(
                NvAudioEffects.C_INT,
                NvAudioEffects.C_POINTER
        );
        public static final MemorySegment ADDR = SYMBOL_LOOKUP.findOrThrow("NvAFX_Reset");
        public static final MethodHandle HANDLE = Linker.nativeLinker().downcallHandle(ADDR, DESC);
    }
    public static FunctionDescriptor NvAFX_Reset$descriptor() {
        return NvAFX_Reset.DESC;
    }
    public static MethodHandle NvAFX_Reset$handle() {
        return NvAFX_Reset.HANDLE;
    }
    public static MemorySegment NvAFX_Reset$address() {
        return NvAFX_Reset.ADDR;
    }
    public static int NvAFX_Reset(MemorySegment effect) {
        var mh$ = NvAFX_Reset.HANDLE;
        try {
            if (TRACE_DOWNCALLS) {
                traceDowncall("NvAFX_Reset", effect);
            }
            return (int)mh$.invokeExact(effect);
        } catch (Error | RuntimeException ex) {
            throw ex;
        } catch (Throwable ex$) {
            throw new AssertionError("should not reach here", ex$);
        }
    }
    private static final MemorySegment NULL = MemorySegment.ofAddress(0L);
    public static MemorySegment NULL() {
        return NULL;
    }
    private static final int _VCRUNTIME_DISABLED_WARNINGS = (int)4514L;
    public static int _VCRUNTIME_DISABLED_WARNINGS() {
        return _VCRUNTIME_DISABLED_WARNINGS;
    }
    private static final int INT8_MIN = (int)-128L;
    public static int INT8_MIN() {
        return INT8_MIN;
    }
    private static final int INT16_MIN = (int)-32768L;
    public static int INT16_MIN() {
        return INT16_MIN;
    }
    private static final int INT32_MIN = (int)-2147483648L;
    public static int INT32_MIN() {
        return INT32_MIN;
    }
    private static final long INT64_MIN = -9223372036854775808L;
    public static long INT64_MIN() {
        return INT64_MIN;
    }
    private static final byte INT8_MAX = (byte)127L;
    public static byte INT8_MAX() {
        return INT8_MAX;
    }
    private static final short INT16_MAX = (short)32767L;
    public static short INT16_MAX() {
        return INT16_MAX;
    }
    private static final int INT32_MAX = (int)2147483647L;
    public static int INT32_MAX() {
        return INT32_MAX;
    }
    private static final long INT64_MAX = 9223372036854775807L;
    public static long INT64_MAX() {
        return INT64_MAX;
    }
    private static final byte UINT8_MAX = (byte)255L;
    public static byte UINT8_MAX() {
        return UINT8_MAX;
    }
    private static final short UINT16_MAX = (short)65535L;
    public static short UINT16_MAX() {
        return UINT16_MAX;
    }
    private static final int UINT32_MAX = (int)4294967295L;
    public static int UINT32_MAX() {
        return UINT32_MAX;
    }
    private static final long UINT64_MAX = -1L;
    public static long UINT64_MAX() {
        return UINT64_MAX;
    }
    private static final int INT_LEAST8_MIN = (int)-128L;
    public static int INT_LEAST8_MIN() {
        return INT_LEAST8_MIN;
    }
    private static final int INT_LEAST16_MIN = (int)-32768L;
    public static int INT_LEAST16_MIN() {
        return INT_LEAST16_MIN;
    }
    private static final int INT_LEAST32_MIN = (int)-2147483648L;
    public static int INT_LEAST32_MIN() {
        return INT_LEAST32_MIN;
    }
    private static final long INT_LEAST64_MIN = -9223372036854775808L;
    public static long INT_LEAST64_MIN() {
        return INT_LEAST64_MIN;
    }
    private static final byte INT_LEAST8_MAX = (byte)127L;
    public static byte INT_LEAST8_MAX() {
        return INT_LEAST8_MAX;
    }
    private static final short INT_LEAST16_MAX = (short)32767L;
    public static short INT_LEAST16_MAX() {
        return INT_LEAST16_MAX;
    }
    private static final int INT_LEAST32_MAX = (int)2147483647L;
    public static int INT_LEAST32_MAX() {
        return INT_LEAST32_MAX;
    }
    private static final long INT_LEAST64_MAX = 9223372036854775807L;
    public static long INT_LEAST64_MAX() {
        return INT_LEAST64_MAX;
    }
    private static final byte UINT_LEAST8_MAX = (byte)255L;
    public static byte UINT_LEAST8_MAX() {
        return UINT_LEAST8_MAX;
    }
    private static final short UINT_LEAST16_MAX = (short)65535L;
    public static short UINT_LEAST16_MAX() {
        return UINT_LEAST16_MAX;
    }
    private static final int UINT_LEAST32_MAX = (int)4294967295L;
    public static int UINT_LEAST32_MAX() {
        return UINT_LEAST32_MAX;
    }
    private static final long UINT_LEAST64_MAX = -1L;
    public static long UINT_LEAST64_MAX() {
        return UINT_LEAST64_MAX;
    }
    private static final int INT_FAST8_MIN = (int)-128L;
    public static int INT_FAST8_MIN() {
        return INT_FAST8_MIN;
    }
    private static final int INT_FAST16_MIN = (int)-2147483648L;
    public static int INT_FAST16_MIN() {
        return INT_FAST16_MIN;
    }
    private static final int INT_FAST32_MIN = (int)-2147483648L;
    public static int INT_FAST32_MIN() {
        return INT_FAST32_MIN;
    }
    private static final long INT_FAST64_MIN = -9223372036854775808L;
    public static long INT_FAST64_MIN() {
        return INT_FAST64_MIN;
    }
    private static final byte INT_FAST8_MAX = (byte)127L;
    public static byte INT_FAST8_MAX() {
        return INT_FAST8_MAX;
    }
    private static final int INT_FAST16_MAX = (int)2147483647L;
    public static int INT_FAST16_MAX() {
        return INT_FAST16_MAX;
    }
    private static final int INT_FAST32_MAX = (int)2147483647L;
    public static int INT_FAST32_MAX() {
        return INT_FAST32_MAX;
    }
    private static final long INT_FAST64_MAX = 9223372036854775807L;
    public static long INT_FAST64_MAX() {
        return INT_FAST64_MAX;
    }
    private static final byte UINT_FAST8_MAX = (byte)255L;
    public static byte UINT_FAST8_MAX() {
        return UINT_FAST8_MAX;
    }
    private static final int UINT_FAST16_MAX = (int)4294967295L;
    public static int UINT_FAST16_MAX() {
        return UINT_FAST16_MAX;
    }
    private static final int UINT_FAST32_MAX = (int)4294967295L;
    public static int UINT_FAST32_MAX() {
        return UINT_FAST32_MAX;
    }
    private static final long UINT_FAST64_MAX = -1L;
    public static long UINT_FAST64_MAX() {
        return UINT_FAST64_MAX;
    }
    private static final long INTPTR_MIN = -9223372036854775808L;
    public static long INTPTR_MIN() {
        return INTPTR_MIN;
    }
    private static final long INTPTR_MAX = 9223372036854775807L;
    public static long INTPTR_MAX() {
        return INTPTR_MAX;
    }
    private static final long UINTPTR_MAX = -1L;
    public static long UINTPTR_MAX() {
        return UINTPTR_MAX;
    }
    private static final long INTMAX_MIN = -9223372036854775808L;
    public static long INTMAX_MIN() {
        return INTMAX_MIN;
    }
    private static final long INTMAX_MAX = 9223372036854775807L;
    public static long INTMAX_MAX() {
        return INTMAX_MAX;
    }
    private static final long UINTMAX_MAX = -1L;
    public static long UINTMAX_MAX() {
        return UINTMAX_MAX;
    }
    private static final long PTRDIFF_MIN = -9223372036854775808L;
    public static long PTRDIFF_MIN() {
        return PTRDIFF_MIN;
    }
    private static final long PTRDIFF_MAX = 9223372036854775807L;
    public static long PTRDIFF_MAX() {
        return PTRDIFF_MAX;
    }
    private static final long SIZE_MAX = -1L;
    public static long SIZE_MAX() {
        return SIZE_MAX;
    }
    private static final int SIG_ATOMIC_MIN = (int)-2147483648L;
    public static int SIG_ATOMIC_MIN() {
        return SIG_ATOMIC_MIN;
    }
    private static final int SIG_ATOMIC_MAX = (int)2147483647L;
    public static int SIG_ATOMIC_MAX() {
        return SIG_ATOMIC_MAX;
    }
    public static MemorySegment NVAFX_EFFECT_DENOISER() {
        class Holder {
            static final MemorySegment NVAFX_EFFECT_DENOISER
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("denoiser");
        }
        return Holder.NVAFX_EFFECT_DENOISER;
    }
    public static MemorySegment NVAFX_EFFECT_DEREVERB() {
        class Holder {
            static final MemorySegment NVAFX_EFFECT_DEREVERB
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("dereverb");
        }
        return Holder.NVAFX_EFFECT_DEREVERB;
    }
    public static MemorySegment NVAFX_EFFECT_DEREVERB_DENOISER() {
        class Holder {
            static final MemorySegment NVAFX_EFFECT_DEREVERB_DENOISER
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("dereverb_denoiser");
        }
        return Holder.NVAFX_EFFECT_DEREVERB_DENOISER;
    }
    public static MemorySegment NVAFX_EFFECT_AEC() {
        class Holder {
            static final MemorySegment NVAFX_EFFECT_AEC
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("aec");
        }
        return Holder.NVAFX_EFFECT_AEC;
    }
    public static MemorySegment NVAFX_EFFECT_SUPERRES() {
        class Holder {
            static final MemorySegment NVAFX_EFFECT_SUPERRES
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("superres");
        }
        return Holder.NVAFX_EFFECT_SUPERRES;
    }
    public static MemorySegment NVAFX_CHAINED_EFFECT_DENOISER_16k_SUPERRES_16k_TO_48k() {
        class Holder {
            static final MemorySegment NVAFX_CHAINED_EFFECT_DENOISER_16k_SUPERRES_16k_TO_48k
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("denoiser16k_superres16kto48k");
        }
        return Holder.NVAFX_CHAINED_EFFECT_DENOISER_16k_SUPERRES_16k_TO_48k;
    }
    public static MemorySegment NVAFX_CHAINED_EFFECT_DEREVERB_16k_SUPERRES_16k_TO_48k() {
        class Holder {
            static final MemorySegment NVAFX_CHAINED_EFFECT_DEREVERB_16k_SUPERRES_16k_TO_48k
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("dereverb16k_superres16kto48k");
        }
        return Holder.NVAFX_CHAINED_EFFECT_DEREVERB_16k_SUPERRES_16k_TO_48k;
    }
    public static MemorySegment NVAFX_CHAINED_EFFECT_DEREVERB_DENOISER_16k_SUPERRES_16k_TO_48k() {
        class Holder {
            static final MemorySegment NVAFX_CHAINED_EFFECT_DEREVERB_DENOISER_16k_SUPERRES_16k_TO_48k
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("dereverb_denoiser16k_superres16kto48k");
        }
        return Holder.NVAFX_CHAINED_EFFECT_DEREVERB_DENOISER_16k_SUPERRES_16k_TO_48k;
    }
    public static MemorySegment NVAFX_CHAINED_EFFECT_SUPERRES_8k_TO_16k_DENOISER_16k() {
        class Holder {
            static final MemorySegment NVAFX_CHAINED_EFFECT_SUPERRES_8k_TO_16k_DENOISER_16k
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("superres8kto16k_denoiser16k");
        }
        return Holder.NVAFX_CHAINED_EFFECT_SUPERRES_8k_TO_16k_DENOISER_16k;
    }
    public static MemorySegment NVAFX_CHAINED_EFFECT_SUPERRES_8k_TO_16k_DEREVERB_16k() {
        class Holder {
            static final MemorySegment NVAFX_CHAINED_EFFECT_SUPERRES_8k_TO_16k_DEREVERB_16k
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("superres8kto16k_dereverb16k");
        }
        return Holder.NVAFX_CHAINED_EFFECT_SUPERRES_8k_TO_16k_DEREVERB_16k;
    }
    public static MemorySegment NVAFX_CHAINED_EFFECT_SUPERRES_8k_TO_16k_DEREVERB_DENOISER_16k() {
        class Holder {
            static final MemorySegment NVAFX_CHAINED_EFFECT_SUPERRES_8k_TO_16k_DEREVERB_DENOISER_16k
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("superres8kto16k_dereverb_denoiser16k");
        }
        return Holder.NVAFX_CHAINED_EFFECT_SUPERRES_8k_TO_16k_DEREVERB_DENOISER_16k;
    }
    public static MemorySegment NVAFX_PARAM_NUM_STREAMS() {
        class Holder {
            static final MemorySegment NVAFX_PARAM_NUM_STREAMS
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("num_streams");
        }
        return Holder.NVAFX_PARAM_NUM_STREAMS;
    }
    public static MemorySegment NVAFX_PARAM_USE_DEFAULT_GPU() {
        class Holder {
            static final MemorySegment NVAFX_PARAM_USE_DEFAULT_GPU
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("use_default_gpu");
        }
        return Holder.NVAFX_PARAM_USE_DEFAULT_GPU;
    }
    public static MemorySegment NVAFX_PARAM_USER_CUDA_CONTEXT() {
        class Holder {
            static final MemorySegment NVAFX_PARAM_USER_CUDA_CONTEXT
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("user_cuda_context");
        }
        return Holder.NVAFX_PARAM_USER_CUDA_CONTEXT;
    }
    public static MemorySegment NVAFX_PARAM_DISABLE_CUDA_GRAPH() {
        class Holder {
            static final MemorySegment NVAFX_PARAM_DISABLE_CUDA_GRAPH
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("disable_cuda_graph");
        }
        return Holder.NVAFX_PARAM_DISABLE_CUDA_GRAPH;
    }
    public static MemorySegment NVAFX_PARAM_ENABLE_VAD() {
        class Holder {
            static final MemorySegment NVAFX_PARAM_ENABLE_VAD
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("enable_vad");
        }
        return Holder.NVAFX_PARAM_ENABLE_VAD;
    }
    public static MemorySegment NVAFX_PARAM_MODEL_PATH(boolean gtx) {
        class Holder {
            static final MemorySegment RTX_KEY = NvAudioEffects.LIBRARY_ARENA.allocateFrom("model_path");
            static final MemorySegment GTX_KEY = NvAudioEffects.LIBRARY_ARENA.allocateFrom("denoiser_model_path");
        }
        return gtx ? Holder.GTX_KEY : Holder.RTX_KEY;
    }
    public static MemorySegment NVAFX_PARAM_INPUT_SAMPLE_RATE() {
        class Holder {
            static final MemorySegment NVAFX_PARAM_INPUT_SAMPLE_RATE
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("input_sample_rate");
        }
        return Holder.NVAFX_PARAM_INPUT_SAMPLE_RATE;
    }
    public static MemorySegment NVAFX_PARAM_OUTPUT_SAMPLE_RATE() {
        class Holder {
            static final MemorySegment NVAFX_PARAM_OUTPUT_SAMPLE_RATE
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("output_sample_rate");
        }
        return Holder.NVAFX_PARAM_OUTPUT_SAMPLE_RATE;
    }
    public static MemorySegment NVAFX_PARAM_NUM_INPUT_SAMPLES_PER_FRAME() {
        class Holder {
            static final MemorySegment NVAFX_PARAM_NUM_INPUT_SAMPLES_PER_FRAME
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("num_input_samples_per_frame");
        }
        return Holder.NVAFX_PARAM_NUM_INPUT_SAMPLES_PER_FRAME;
    }
    public static MemorySegment NVAFX_PARAM_NUM_OUTPUT_SAMPLES_PER_FRAME() {
        class Holder {
            static final MemorySegment NVAFX_PARAM_NUM_OUTPUT_SAMPLES_PER_FRAME
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("num_output_samples_per_frame");
        }
        return Holder.NVAFX_PARAM_NUM_OUTPUT_SAMPLES_PER_FRAME;
    }
    public static MemorySegment NVAFX_PARAM_NUM_INPUT_CHANNELS() {
        class Holder {
            static final MemorySegment NVAFX_PARAM_NUM_INPUT_CHANNELS
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("num_input_channels");
        }
        return Holder.NVAFX_PARAM_NUM_INPUT_CHANNELS;
    }
    public static MemorySegment NVAFX_PARAM_NUM_OUTPUT_CHANNELS() {
        class Holder {
            static final MemorySegment NVAFX_PARAM_NUM_OUTPUT_CHANNELS
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("num_output_channels");
        }
        return Holder.NVAFX_PARAM_NUM_OUTPUT_CHANNELS;
    }
    public static MemorySegment NVAFX_PARAM_INTENSITY_RATIO() {
        class Holder {
            static final MemorySegment NVAFX_PARAM_INTENSITY_RATIO
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("intensity_ratio");
        }
        return Holder.NVAFX_PARAM_INTENSITY_RATIO;
    }
    public static MemorySegment NVAFX_PARAM_DENOISER_MODEL_PATH() {
        class Holder {
            static final MemorySegment NVAFX_PARAM_DENOISER_MODEL_PATH
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("model_path");
        }
        return Holder.NVAFX_PARAM_DENOISER_MODEL_PATH;
    }
    public static MemorySegment NVAFX_PARAM_DENOISER_SAMPLE_RATE() {
        class Holder {
            static final MemorySegment NVAFX_PARAM_DENOISER_SAMPLE_RATE
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("sample_rate");
        }
        return Holder.NVAFX_PARAM_DENOISER_SAMPLE_RATE;
    }
    public static MemorySegment NVAFX_PARAM_DENOISER_NUM_SAMPLES_PER_FRAME() {
        class Holder {
            static final MemorySegment NVAFX_PARAM_DENOISER_NUM_SAMPLES_PER_FRAME
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("num_samples_per_frame");
        }
        return Holder.NVAFX_PARAM_DENOISER_NUM_SAMPLES_PER_FRAME;
    }
    public static MemorySegment NVAFX_PARAM_DENOISER_NUM_CHANNELS() {
        class Holder {
            static final MemorySegment NVAFX_PARAM_DENOISER_NUM_CHANNELS
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("num_channels");
        }
        return Holder.NVAFX_PARAM_DENOISER_NUM_CHANNELS;
    }
    public static MemorySegment NVAFX_PARAM_DENOISER_INTENSITY_RATIO() {
        class Holder {
            static final MemorySegment NVAFX_PARAM_DENOISER_INTENSITY_RATIO
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("intensity_ratio");
        }
        return Holder.NVAFX_PARAM_DENOISER_INTENSITY_RATIO;
    }
    public static MemorySegment NVAFX_PARAM_NUM_CHANNELS() {
        class Holder {
            static final MemorySegment NVAFX_PARAM_NUM_CHANNELS
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("num_channels");
        }
        return Holder.NVAFX_PARAM_NUM_CHANNELS;
    }
    public static MemorySegment NVAFX_PARAM_SAMPLE_RATE() {
        class Holder {
            static final MemorySegment NVAFX_PARAM_SAMPLE_RATE
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("sample_rate");
        }
        return Holder.NVAFX_PARAM_SAMPLE_RATE;
    }
    public static MemorySegment NVAFX_PARAM_NUM_SAMPLES_PER_FRAME() {
        class Holder {
            static final MemorySegment NVAFX_PARAM_NUM_SAMPLES_PER_FRAME
                    = NvAudioEffects.LIBRARY_ARENA.allocateFrom("num_samples_per_frame");
        }
        return Holder.NVAFX_PARAM_NUM_SAMPLES_PER_FRAME;
    }
}
