package net.typeblog.socks

object System {
    init {
        java.lang.System.loadLibrary("system")
    }

    @JvmStatic
    external fun getABI(): String

    @JvmStatic
    external fun sendfd(fd: Int): Int

    @JvmStatic
    external fun jniclose(fd: Int)
}
