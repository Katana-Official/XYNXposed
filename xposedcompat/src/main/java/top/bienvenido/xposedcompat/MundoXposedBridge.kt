package top.bienvenido.xposedcompat

import android.content.Context
import com.zxc.jtik.Jtik
import com.zxc.jtik.JtikConfig
import com.zxc.jtik.MethodHook
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.reflect.Executable
import java.lang.reflect.Member
import java.util.LinkedList

object MundoXposedBridge {
    fun init(context: Context)
    {
        try {
//            JtikConfig.needHookSystemClass = true
            Jtik.init(context)
        }catch (e : Exception)
        {
            JKLog.e(e)
        }
    }
    @JvmStatic
    fun hookMethod(member: Member, callback : XposedBridge.AdditionalHookInfo)
    {
        try {
            if(member !is Executable) return
            val newParam = XC_MethodHook.MethodHookParam()
            val passedMethodHook = LinkedList<XC_MethodHook>()
            newParam.method = member
            val hookItem = MethodHook.Builder().setMethodEnterListener { thiz, args ->
                passedMethodHook.clear()
                newParam.thisObject = thiz
                newParam.args = args
                newParam.result = null
                newParam.thisObject = null
                newParam.returnEarly = false
                newParam.throwable = null
                for(oneMethodHook in callback.callbacks.snapshot)
                {
                    if(oneMethodHook is XC_MethodHook)
                    {
                        oneMethodHook.callBeforeHookedMethod(newParam)
                        if(newParam.returnEarly)
                        {
                            break
                        }
                        else passedMethodHook.addFirst(oneMethodHook)
                    }
                }
            }.apply {
                for(index in 0 until member.parameterCount)
                {
                    setParamModifier(index) {
                        _, _ ->
                        return@setParamModifier newParam.args[index]
                    }
                }
            }.setMethodExitListener { thiz, origin ->
                if(newParam.returnEarly)
                    return@setMethodExitListener newParam.result
                newParam.setResult(origin)
                for(oneMethodHook in passedMethodHook)
                {
                    oneMethodHook.callAfterHookedMethod(newParam)
                }
                return@setMethodExitListener newParam.result
            }.build()
            Jtik.hook(
                member,
                hookItem
            )
        }catch (e : Exception)
        {
            JKLog.e(e)
        }
    }
}