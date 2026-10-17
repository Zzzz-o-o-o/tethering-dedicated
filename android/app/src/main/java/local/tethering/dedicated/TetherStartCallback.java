package local.tethering.dedicated;

/**
 * テザリング開始の結果を受け取る（仕様 F-37）。OS の非公開クラス ConnectivityManager.OnStartTetheringCallback を継承する。
 * Kotlin からは名前に「$」を含む親クラスを扱いにくいため、ここだけ Java で書く。
 */
public final class TetherStartCallback extends android.net.ConnectivityManager$OnStartTetheringCallback {
    public interface Listener {
        void onResult(boolean started);
    }

    private final Listener listener;

    public TetherStartCallback(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void onTetheringStarted() {
        listener.onResult(true);
    }

    @Override
    public void onTetheringFailed() {
        listener.onResult(false);
    }
}
