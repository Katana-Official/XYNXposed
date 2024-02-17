package de.robv.android.xposed;

import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.util.Log;

import com.android.internal.os.RuntimeInit;
import com.android.internal.os.ZygoteInit;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import dalvik.system.PathClassLoader;
import de.robv.android.xposed.XC_MethodHook.MethodHookParam;
import de.robv.android.xposed.callbacks.XC_InitPackageResources;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import top.bienvenido.xposedcompat.JKLog;
import top.bienvenido.xposedcompat.MundoXposedBridge;

import static de.robv.android.xposed.XposedHelpers.getIntField;
import static de.robv.android.xposed.XposedHelpers.setObjectField;

/**
 * This class contains most of Xposed's central logic, such as initialization and callbacks used by
 * the native side. It also includes methods to add new hooks.
 */
@SuppressWarnings("JniMissingFunction")
public final class XposedBridge {
	/**
	 * The system class loader which can be used to locate Android framework classes.
	 * Application classes cannot be retrieved from it.
	 *
	 * @see ClassLoader#getSystemClassLoader
	 */
	public static final ClassLoader BOOTCLASSLOADER = ClassLoader.getSystemClassLoader();

	/** @hide */
	public static final String TAG = "Xposed";

	/** @deprecated Use {@link #getXposedVersion()} instead. */
	@Deprecated
	public static int XPOSED_BRIDGE_VERSION = 92;

	/*package*/ static boolean isZygote = true;
	private static final int RUNTIME_DALVIK = 1;
	private static final int RUNTIME_ART = 2;

	/*package*/ static boolean disableHooks = false;

	// This field is set "magically" on MIUI.
	/*package*/ static long BOOT_START_TIME;

	private static final Object[] EMPTY_ARRAY = new Object[0];

	// built-in handlers
	private static final Map<Member, CopyOnWriteSortedSet<XC_MethodHook>> sHookedMethodCallbacks = new HashMap<>();
	/*package*/ static final CopyOnWriteSortedSet<XC_LoadPackage> sLoadedPackageCallbacks = new CopyOnWriteSortedSet<>();
	/*package*/ static final CopyOnWriteSortedSet<XC_InitPackageResources> sInitPackageResourcesCallbacks = new CopyOnWriteSortedSet<>();

	private XposedBridge() {}

	/**
	 * Called when native methods and other things are initialized, but before preloading classes etc.
	 * @hide
	 */
	@SuppressWarnings("deprecation")
	protected static void main(String[] args) {
		// Initialize the Xposed framework and modules
		try {
			initXResources();
			SELinuxHelper.initOnce();
			SELinuxHelper.initForProcess(null);
			XPOSED_BRIDGE_VERSION = getXposedVersion();
			if (isZygote) {
				XposedInit.hookResources();
				XposedInit.initForZygote();
			}
			XposedInit.loadModules();
		} catch (Throwable t) {
			Log.e(TAG, "Errors during Xposed initialization", t);
			disableHooks = true;
		}

		// Call the original startup code
		if (isZygote) {
			ZygoteInit.main(args);
		} else {
			RuntimeInit.main(args);
		}
	}

	/** @hide */
	protected static final class ToolEntryPoint {
		protected static void main(String[] args) {
			isZygote = false;
			XposedBridge.main(args);
		}
	}

	private static void initXResources() throws IOException {
		// Create XResourcesSuperClass.
		Resources res = Resources.getSystem();
		File resDexFile = ensureSuperDexFile("XResources", res.getClass(), Resources.class);

		// Create XTypedArraySuperClass.
		Class<?> taClass = TypedArray.class;
		try {
			TypedArray ta = res.obtainTypedArray(res.getIdentifier("preloaded_drawables", "array", "android"));
			taClass = ta.getClass();
			ta.recycle();
		} catch (Resources.NotFoundException nfe) {
			XposedBridge.log(nfe);
		}
		Runtime.getRuntime().gc();
		File taDexFile = ensureSuperDexFile("XTypedArray", taClass, TypedArray.class);

		// Inject a ClassLoader for the created classes as parent of XposedBridge's ClassLoader.
		ClassLoader myCL =  XposedBridge.class.getClassLoader();
		String paths = resDexFile.getAbsolutePath() + File.pathSeparator + taDexFile.getAbsolutePath();
		PathClassLoader dummyCL = new PathClassLoader(paths, myCL.getParent());
		setObjectField(myCL, "parent", dummyCL);
	}

	@SuppressLint("SetWorldReadable")
	private static File ensureSuperDexFile(String clz, Class<?> realSuperClz, Class<?> topClz) throws IOException {
		File dexFile = DexCreator.ensure(clz, realSuperClz, topClz);
		dexFile.setReadable(true, false);
		return dexFile;
	}

	/**
	 * Returns the currently installed version of the Xposed framework.
	 */
	public static int getXposedVersion()
	{
		return 92;
	}

	/**
	 * Writes a message to the Xposed error log.
	 *
	 * <p class="warning"><b>DON'T FLOOD THE LOG!!!</b> This is only meant for error logging.
	 * If you want to write information/debug messages, use logcat.
	 *
	 * @param text The log message.
	 */
	public synchronized static void log(String text) {
		Log.i(TAG, text);
	}

	/**
	 * Logs a stack trace to the Xposed error log.
	 *
	 * <p class="warning"><b>DON'T FLOOD THE LOG!!!</b> This is only meant for error logging.
	 * If you want to write information/debug messages, use logcat.
	 *
	 * @param t The Throwable object for the stack trace.
	 */
	public synchronized static void log(Throwable t) {
		Log.e(TAG, Log.getStackTraceString(t));
	}

	/**
	 * Hook any method (or constructor) with the specified callback. See below for some wrappers
	 * that make it easier to find a method/constructor in one step.
	 *
	 * @param hookMethod The method to be hooked.
	 * @param callback The callback to be executed when the hooked method is called.
	 * @return An object that can be used to remove the hook.
	 *
	 * @see XposedHelpers#findAndHookMethod(String, ClassLoader, String, Object...)
	 * @see XposedHelpers#findAndHookMethod(Class, String, Object...)
	 * @see #hookAllMethods
	 * @see XposedHelpers#findAndHookConstructor(String, ClassLoader, Object...)
	 * @see XposedHelpers#findAndHookConstructor(Class, Object...)
	 * @see #hookAllConstructors
	 */
	public static XC_MethodHook.Unhook hookMethod(Member hookMethod, XC_MethodHook callback) {
//		if (!(hookMethod instanceof Method) && !(hookMethod instanceof Constructor<?>)) {
//			throw new IllegalArgumentException("Only methods and constructors can be hooked: " + hookMethod.toString());
//		} else if (hookMethod.getDeclaringClass().isInterface()) {
//			throw new IllegalArgumentException("Cannot hook interfaces: " + hookMethod.toString());
//		} else if (Modifier.isAbstract(hookMethod.getModifiers())) {
//			throw new IllegalArgumentException("Cannot hook abstract methods: " + hookMethod.toString());
//		}
		if(!(hookMethod instanceof Executable))
			throw new IllegalArgumentException("Only methods and constructors can be hooked: " + hookMethod.toString());

		boolean newMethod = false;
		CopyOnWriteSortedSet<XC_MethodHook> callbacks;
		synchronized (sHookedMethodCallbacks) {
			callbacks = sHookedMethodCallbacks.get(hookMethod);
			if (callbacks == null) {
				callbacks = new CopyOnWriteSortedSet<>();
				sHookedMethodCallbacks.put(hookMethod, callbacks);
				newMethod = true;
			}
		}
		callbacks.add(callback);

		if (newMethod) {

			AdditionalHookInfo additionalInfo = new AdditionalHookInfo(callbacks);
			hookMethodNative(hookMethod, additionalInfo);
		}

		return callback.new Unhook(hookMethod);
	}

	/**
	 * Removes the callback for a hooked method/constructor.
	 *
	 * @deprecated Use {@link XC_MethodHook.Unhook#unhook} instead. An instance of the {@code Unhook}
	 * class is returned when you hook the method.
	 *
	 * @param hookMethod The method for which the callback should be removed.
	 * @param callback The reference to the callback as specified in {@link #hookMethod}.
	 */
	@Deprecated
	public static void unhookMethod(Member hookMethod, XC_MethodHook callback) {
		CopyOnWriteSortedSet<XC_MethodHook> callbacks;
		synchronized (sHookedMethodCallbacks) {
			callbacks = sHookedMethodCallbacks.get(hookMethod);
			if (callbacks == null)
				return;
		}
		callbacks.remove(callback);
	}

	/**
	 * Hooks all methods with a certain name that were declared in the specified class. Inherited
	 * methods and constructors are not considered. For constructors, use
	 * {@link #hookAllConstructors} instead.
	 *
	 * @param hookClass The class to check for declared methods.
	 * @param methodName The name of the method(s) to hook.
	 * @param callback The callback to be executed when the hooked methods are called.
	 * @return A set containing one object for each found method which can be used to unhook it.
	 */
	@SuppressWarnings("UnusedReturnValue")
	public static Set<XC_MethodHook.Unhook> hookAllMethods(Class<?> hookClass, String methodName, XC_MethodHook callback) {
		Set<XC_MethodHook.Unhook> unhooks = new HashSet<>();
		for (Member method : hookClass.getDeclaredMethods())
			if (method.getName().equals(methodName))
				unhooks.add(hookMethod(method, callback));
		return unhooks;
	}

	/**
	 * Hook all constructors of the specified class.
	 *
	 * @param hookClass The class to check for constructors.
	 * @param callback The callback to be executed when the hooked constructors are called.
	 * @return A set containing one object for each found constructor which can be used to unhook it.
	 */
	@SuppressWarnings("UnusedReturnValue")
	public static Set<XC_MethodHook.Unhook> hookAllConstructors(Class<?> hookClass, XC_MethodHook callback) {
		Set<XC_MethodHook.Unhook> unhooks = new HashSet<>();
		for (Member constructor : hookClass.getDeclaredConstructors())
			unhooks.add(hookMethod(constructor, callback));
		return unhooks;
	}

	/**
	 * This method is called as a replacement for hooked methods.
	 */
	private static Object handleHookedMethod(Member method, int originalMethodId, Object additionalInfoObj,
			Object thisObject, Object[] args) throws Throwable {
		AdditionalHookInfo additionalInfo = (AdditionalHookInfo) additionalInfoObj;

		if (disableHooks) {
            return invokeOriginalMethodNative(method, originalMethodId, thisObject, args);
        }

		Object[] callbacksSnapshot = additionalInfo.callbacks.getSnapshot();
		final int callbacksLength = callbacksSnapshot.length;
		if (callbacksLength == 0) {
            return invokeOriginalMethodNative(method, originalMethodId, thisObject, args);
        }

		MethodHookParam param = new MethodHookParam();
		param.method = method;
		param.thisObject = thisObject;
		param.args = args;

		// call "before method" callbacks
		int beforeIdx = 0;
		do {
			try {
				((XC_MethodHook) callbacksSnapshot[beforeIdx]).beforeHookedMethod(param);
			} catch (Throwable t) {
				XposedBridge.log(t);

				// reset result (ignoring what the unexpectedly exiting callback did)
				param.setResult(null);
				param.returnEarly = false;
				continue;
			}

			if (param.returnEarly) {
				// skip remaining "before" callbacks and corresponding "after" callbacks
				beforeIdx++;
				break;
			}
		} while (++beforeIdx < callbacksLength);

		// call original method if not requested otherwise
		if (!param.returnEarly) {
            param.setResult(invokeOriginalMethodNative(method, originalMethodId, param.thisObject, param.args));
        }

		// call "after method" callbacks
		int afterIdx = beforeIdx - 1;
		do {
			Object lastResult =  param.getResult();
			Throwable lastThrowable = param.getThrowable();

			try {
				((XC_MethodHook) callbacksSnapshot[afterIdx]).afterHookedMethod(param);
			} catch (Throwable t) {
				XposedBridge.log(t);

				// reset to last result (ignoring what the unexpectedly exiting callback did)
				if (lastThrowable == null)
					param.setResult(lastResult);
				else
					param.setThrowable(lastThrowable);
			}
		} while (--afterIdx >= 0);

		// return
		if (param.hasThrowable())
			throw param.getThrowable();
		else
			return param.getResult();
	}

	/**
	 * Adds a callback to be executed when an app ("Android package") is loaded.
	 *
	 * <p class="note">You probably don't need to call this. Simply implement {@link IXposedHookLoadPackage}
	 * in your module class and Xposed will take care of registering it as a callback.
	 *
	 * @param callback The callback to be executed.
	 * @hide
	 */
	public static void hookLoadPackage(XC_LoadPackage callback) {
		synchronized (sLoadedPackageCallbacks) {
			sLoadedPackageCallbacks.add(callback);
		}
	}

	/**
	 * Adds a callback to be executed when the resources for an app are initialized.
	 *
	 * <p class="note">You probably don't need to call this. Simply implement {@link IXposedHookInitPackageResources}
	 * in your module class and Xposed will take care of registering it as a callback.
	 *
	 * @param callback The callback to be executed.
	 * @hide
	 */
	public static void hookInitPackageResources(XC_InitPackageResources callback) {
		synchronized (sInitPackageResourcesCallbacks) {
			sInitPackageResourcesCallbacks.add(callback);
		}
	}

	/**
	 * Intercept every call to the specified method and call a handler function instead.
	 * @param method The method to intercept
	 */
	private static void hookMethodNative(Member method, AdditionalHookInfo additionalInfo)
	{
		MundoXposedBridge.hookMethod(
				method,
				additionalInfo
		);
	}

	private synchronized static Object invokeOriginalMethodNative(Member method, int methodId, Object thisObject, Object[] args)
	{
		CopyOnWriteSortedSet<XC_MethodHook> callbacks = sHookedMethodCallbacks.get(method);
		Object res = null;
		if(callbacks != null)
		{
			Object[] e = callbacks.elements;
			callbacks.elements = new XC_MethodHook[]{};
			try{
				if(method instanceof Constructor)
				{
					res = ((Constructor<?>) method).newInstance(args);
				}
				else if(method instanceof Method)
				{
					res = ((Method) method).invoke(thisObject, args);
				}
			}catch (Throwable t)
			{
				JKLog.INSTANCE.e(t);
			}
			callbacks.elements = e;
		}
		return res;
	}

	/**
	 * Basically the same as {@link Method#invoke}, but calls the original method
	 * as it was before the interception by Xposed. Also, access permissions are not checked.
	 *
	 * <p class="caution">There are very few cases where this method is needed. A common mistake is
	 * to replace a method and then invoke the original one based on dynamic conditions. This
	 * creates overhead and skips further hooks by other modules. Instead, just hook (don't replace)
	 * the method and call {@code param.setResult(null)} in {@link XC_MethodHook#beforeHookedMethod}
	 * if the original method should be skipped.
	 *
	 * @param method The method to be called.
	 * @param thisObject For non-static calls, the "this" pointer, otherwise {@code null}.
	 * @param args Arguments for the method call as Object[] array.
	 * @return The result returned from the invoked method.
	 * @throws NullPointerException
	 *             if {@code receiver == null} for a non-static method
	 * @throws IllegalAccessException
	 *             if this method is not accessible (see {@link AccessibleObject})
	 * @throws IllegalArgumentException
	 *             if the number of arguments doesn't match the number of parameters, the receiver
	 *             is incompatible with the declaring class, or an argument could not be unboxed
	 *             or converted by a widening conversion to the corresponding parameter type
	 * @throws InvocationTargetException
	 *             if an exception was thrown by the invoked method
	 */
	public static Object invokeOriginalMethod(Member method, Object thisObject, Object[] args)
			throws NullPointerException, IllegalAccessException, IllegalArgumentException, InvocationTargetException {
		if (args == null) {
			args = EMPTY_ARRAY;
		}

		return invokeOriginalMethodNative(method, 0, thisObject, args);
	}

	/** @hide */
	public static final class CopyOnWriteSortedSet<E> {
		public transient volatile Object[] elements = EMPTY_ARRAY;

		@SuppressWarnings("UnusedReturnValue")
		public synchronized boolean add(E e) {
			int index = indexOf(e);
			if (index >= 0)
				return false;

			Object[] newElements = new Object[elements.length + 1];
			System.arraycopy(elements, 0, newElements, 0, elements.length);
			newElements[elements.length] = e;
			Arrays.sort(newElements);
			elements = newElements;
			return true;
		}

		@SuppressWarnings("UnusedReturnValue")
		public synchronized boolean remove(E e) {
			int index = indexOf(e);
			if (index == -1)
				return false;

			Object[] newElements = new Object[elements.length - 1];
			System.arraycopy(elements, 0, newElements, 0, index);
			System.arraycopy(elements, index + 1, newElements, index, elements.length - index - 1);
			elements = newElements;
			return true;
		}

		private int indexOf(Object o) {
			for (int i = 0; i < elements.length; i++) {
				if (o.equals(elements[i]))
					return i;
			}
			return -1;
		}

		public Object[] getSnapshot() {
			return elements;
		}
	}

	public static class AdditionalHookInfo {
		public final CopyOnWriteSortedSet<XC_MethodHook> callbacks;

		private AdditionalHookInfo(CopyOnWriteSortedSet<XC_MethodHook> callbacks) {
			this.callbacks = callbacks;
		}
	}
}
