package top.bienvenido.xynxposedframework.tests

import androidx.annotation.Keep
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers

object SimpleTestCases {
    @Keep
    fun method1()
    {
        println("Test cases 1!")
    }
    @Keep
    fun method2(arg1 : Any?)
    {
        println("Test cases 2 $arg1!")
    }
    @Keep
    fun method3(arg1 : Any?) : Any?
    {
        println("Test cases 3 $arg1!")
        return SimpleTestCases::class.qualifiedName
    }
    fun test()
    {
        XposedHelpers.findAndHookMethod(
            SimpleTestCases::class.java,
            "method1",
            object : XC_MethodHook()
            {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    super.afterHookedMethod(param)
                    println("After method 1")
                }
            }
        )
        XposedHelpers.findAndHookMethod(
            SimpleTestCases::class.java,
            "method2",
            Any::class.java,
            object : XC_MethodHook()
            {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    super.beforeHookedMethod(param)
                    param.args[0] = "Hi"
                }

                override fun afterHookedMethod(param: MethodHookParam?) {
                    super.afterHookedMethod(param)
                    println("After method 2")
                }
            }
        )
        XposedHelpers.findAndHookMethod(
            SimpleTestCases::class.java,
            "method3",
            Any::class.java,
            object : XC_MethodHook()
            {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    super.beforeHookedMethod(param)
                    param.args[0] = "Any"
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    super.afterHookedMethod(param)
                    println("Method 3 result ${param.getResult()}")
                    param.setResult(String::class.qualifiedName)
                }
            }
        )
        method1()
        method2("Hello")
        println("Method 3 ${method3("Object")}")
    }
}