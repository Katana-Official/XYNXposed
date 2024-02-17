package top.bienvenido.xynxposedframework

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.Keep
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import top.bienvenido.xynxposedframework.tests.SimpleTestCases
import top.bienvenido.xynxposedframework.ui.theme.XYNXposedFrameworkTheme

class MainActivity : ComponentActivity() {
    @Keep
    fun testHookFunction(entrance : String) : String
    {
        return entrance
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        XposedBridge.hookMethod(
            MainActivity::class.java.getMethod("testHookFunction", String::class.java),
            object : XC_MethodHook()
            {
                override fun callBeforeHookedMethod(param: MethodHookParam) {
                    super.callBeforeHookedMethod(param)
                    Toast.makeText(
                        this@MainActivity,
                        "Parameter 1: "+param.args[0],
                        Toast.LENGTH_SHORT
                    ).show()
//                    param.args[0] = "Hooked"
                }

                override fun afterHookedMethod(param: MethodHookParam) {
                    super.afterHookedMethod(param)
                    param.setResult("Hooked")
                }
            }
        )
        setContent {
            XYNXposedFrameworkTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        name = testHookFunction("Unhooked"),
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
        SimpleTestCases.test()
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    XYNXposedFrameworkTheme {
        Greeting("Android")
    }
}