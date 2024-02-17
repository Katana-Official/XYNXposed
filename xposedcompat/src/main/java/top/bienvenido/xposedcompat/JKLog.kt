package top.bienvenido.xposedcompat

import android.util.Log

object JKLog {
    private val debug = BuildConfig.DEBUG
    private val TAG = JKLog::class.java.simpleName
    fun e(e : Throwable)
    {
        if(debug)
            Log.e(
                TAG,
                e.message,
                e
            )
    }
    fun d(msg : String)
    {
        if(debug)
            Log.d(
                TAG,
                msg
            )
    }
}