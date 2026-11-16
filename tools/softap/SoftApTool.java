package local.tethering.dedicated.tools;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * PC のスクリプト（tools/adb-setup.ps1 の -MaxClients）が、ADB のシェルの権限で端末に動かす補助プログラム（v0.11.0）。
 * OS のアクセスポイントの設定（保存されたもの）のうち、同時接続台数の上限だけを読み書きする（Android 11 以降）。
 * 通常のアプリはこの設定を変えられない（システムの権限 NETWORK_SETTINGS が要る）が、ADB のシェルはこの権限を持つ。
 * OS の内部の仕組み（IWifiManager）をリフレクションで呼ぶ。名前・パスワードなどほかの設定は変えず、表示もしない。
 *
 * 使い方（端末で）: CLASSPATH=/data/local/tmp/td-softap.dex app_process /system/bin local.tethering.dedicated.tools.SoftApTool get|set 台数
 * 出力: maxClients=台数（0 = 制限なし）。set では、その前に result=true|false。失敗したら error=理由
 * 作り方: tools/softap/build-dex.ps1（JDK と Android SDK の d8 で td-softap.dex にする）
 */
public final class SoftApTool {
    /** 呼び出し元のパッケージ（OS は、呼び出し元の UID のものか確かめる。ADB のシェルは com.android.shell） */
    private static final String SHELL_PACKAGE = "com.android.shell";

    public static void main(String[] args) {
        try {
            Object wifi = wifiService();
            if (args.length == 1 && args[0].equals("get")) {
                printMax(call(wifi, "getSoftApConfiguration"));
                return;
            }
            if (args.length == 2 && args[0].equals("set")) {
                int n = Integer.parseInt(args[1]);
                if (n < 0) throw new IllegalArgumentException("negative");
                Object current = call(wifi, "getSoftApConfiguration");
                Class<?> configClass = Class.forName("android.net.wifi.SoftApConfiguration");
                Class<?> builderClass = Class.forName("android.net.wifi.SoftApConfiguration$Builder");
                Object builder = builderClass.getConstructor(configClass).newInstance(current);
                builderClass.getMethod("setMaxNumberOfClients", int.class).invoke(builder, n);
                Object next = builderClass.getMethod("build").invoke(builder);
                Method set = method(wifi.getClass(), "setSoftApConfiguration", 2);
                System.out.println("result=" + set.invoke(wifi, next, SHELL_PACKAGE));
                // OS が受け付けて保存した値を読み直す（対応していない端末では、OS が 0 に戻すことがある）
                printMax(call(wifi, "getSoftApConfiguration"));
                return;
            }
            System.out.println("error=usage: get | set <n>");
            System.exit(2);
        } catch (Throwable t) {
            Throwable c = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            System.out.println("error=" + c.getClass().getSimpleName() + ": " + c.getMessage());
            System.exit(1);
        }
    }

    /** OS の Wi-Fi のサービス（IWifiManager） */
    private static Object wifiService() throws Exception {
        Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "wifi");
        if (binder == null) throw new IllegalStateException("no wifi service");
        Class<?> stub = Class.forName("android.net.wifi.IWifiManager$Stub");
        return stub.getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
    }

    private static Object call(Object target, String name) throws Exception {
        return method(target.getClass(), name, 0).invoke(target);
    }

    /** 名前が {@code name} で引数が {@code count} 個のメソッド（OS の版で引数の型が変わっても見つかるよう、数で探す） */
    private static Method method(Class<?> c, String name, int count) throws NoSuchMethodException {
        for (Method m : c.getMethods()) {
            if (m.getName().equals(name) && m.getParameterTypes().length == count) return m;
        }
        throw new NoSuchMethodException(name);
    }

    private static void printMax(Object config) throws Exception {
        if (config == null) throw new IllegalStateException("no config");
        System.out.println("maxClients=" + config.getClass().getMethod("getMaxNumberOfClients").invoke(config));
    }
}
