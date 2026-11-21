package black.com.android.internal.content;

import java.io.File;
import top.niunaijun.blackreflection.annotation.BClassName;
import top.niunaijun.blackreflection.annotation.BField;
import top.niunaijun.blackreflection.annotation.BParamClassName;
import top.niunaijun.blackreflection.annotation.BStaticMethod;
import top.niunaijun.blackreflection.annotation.BClassNameNotProcess;
import top.niunaijun.blackreflection.annotation.BConstructorNotProcess;
import top.niunaijun.blackreflection.annotation.BFieldNotProcess;
import top.niunaijun.blackreflection.annotation.BFieldSetNotProcess;
import top.niunaijun.blackreflection.annotation.BFieldCheckNotProcess;
import top.niunaijun.blackreflection.annotation.BMethodCheckNotProcess;

@BClassNameNotProcess("com.android.internal.content.NativeLibraryHelper")
public interface NativeLibraryHelperStatic {
    @BMethodCheckNotProcess
    java.lang.reflect.Method _check_copyNativeBinaries(@BParamClassName("com.android.internal.content.NativeLibraryHelper$Handle") Object Handle0, File File1, String String2);
    Integer copyNativeBinaries(@BParamClassName("com.android.internal.content.NativeLibraryHelper$Handle") Object Handle0, File File1, String String2);
    @BMethodCheckNotProcess
    java.lang.reflect.Method _check_findSupportedAbi(@BParamClassName("com.android.internal.content.NativeLibraryHelper$Handle") Object Handle0, @BParamClassName("[Ljava.lang.String;") String[] strings);
    Integer findSupportedAbi(@BParamClassName("com.android.internal.content.NativeLibraryHelper$Handle") Object Handle0, @BParamClassName("[Ljava.lang.String;") String[] strings);
}
