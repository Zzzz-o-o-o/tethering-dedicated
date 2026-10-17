package android.net;

/**
 * コンパイルのための宣言（中身は OS と同じ形の空の実装）。
 * Android 10 の ConnectivityManager.OnStartTetheringCallback は非公開（システム API）で SDK に含まれないため、
 * 同じ名前のクラスをここで宣言して TetherStartCallback から継承できるようにする。
 * 実行時は OS（ブートクラスパス）の同名クラスが先に読み込まれるので、このクラスは使われない。
 */
public abstract class ConnectivityManager$OnStartTetheringCallback {
    public void onTetheringStarted() {
    }

    public void onTetheringFailed() {
    }
}
