# 外部ライブラリは使っていない（マニフェストに書いたアクティビティ・サービス・レシーバーは AGP が自動で残す）。
#
# テザリングの開始（仕様 F-37）: OS の非公開クラス ConnectivityManager.OnStartTetheringCallback を継承したクラスを
# リフレクションで OS に渡す。実行時の親クラスは OS のものなので、R8 がクラスをまとめたり名前を変えたりしないようにする
-keep class android.net.ConnectivityManager$OnStartTetheringCallback { *; }
-keep class local.tethering.dedicated.TetherStartCallback { *; }
