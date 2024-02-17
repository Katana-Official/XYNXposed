package top.bienvenido.xynxposedframework

import android.app.Application
import android.content.Context
import top.bienvenido.xposedcompat.MundoXposedBridge

class XApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        MundoXposedBridge.init(base)
    }
}