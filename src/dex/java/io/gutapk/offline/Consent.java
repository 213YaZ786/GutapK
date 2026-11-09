package io.gutapk.offline;

/*
 * The consent window a game opens at its first launch. An offline build
 * turns the SDK's show call into a call to these, which answer in its place
 * as FakeWifiConnection's hooks answer ConnectivityManager's: the SDK is not
 * asked, the answer is made up and handed to the one who asked. Here the
 * answer is the user's refusal, told to the delegate the game registered with
 * the SDK, exactly as if the user had closed the window with "no". When no
 * delegate can be found the original call is made, so the game never waits
 * for an answer that cannot come.
 *
 * FakeWifiConnection reaches the fields and methods it needs with Xposed's
 * helpers, so the delegate is found and called with their reflection steps.
 *
 * https://github.com/lemonsqueeze/FakeWifiConnection/blob/master/FakeWifiConnection/src/com/lemonsqueeze/fakewificonnection/Main.java#L77-L161
 * https://github.com/rovo89/XposedBridge/blob/master/src/de/robv/android/xposed/XposedHelpers.java#findFirstFieldByExactType
 * https://github.com/rovo89/XposedBridge/blob/master/src/de/robv/android/xposed/XposedHelpers.java#findMethodExact
 * https://github.com/rovo89/XposedBridge/blob/master/src/de/robv/android/xposed/XposedHelpers.java#callMethod
 *
 * Not ported: doit_networkinfo, doit_allnetworkinfo, getFakeNetworkInfo, createNetworkInfo (network answers, Net.java has them: doit_consent, getFakeDelegate and createAnswer below take the same steps for a consent window)
 * Not ported: push (an answer here is one value, not an array to grow)
 * Not ported: createWifiSsid (WifiManager's answer, a game asks no SSID of a consent window)
 * Not ported: fieldCache, methodCache, getParametersString, findMethodBestMatch (a call is made once per launch, nothing is cached or matched by best fit)
 * Not ported: InvocationTargetError, XposedBridge.log (the game's own error is thrown as it is, one line goes to Server.LOG)
 *
 * Copyright (C) 2014 lemonsqueeze, GPL-3.0-or-later. Copyright 2013 rovo89 and Tungstwenty, Apache License 2.0.
 */

import android.app.Activity;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public final class Consent {

    /** What the SDK tells its delegate when the user answered: the games' own method, true for accepted. */
    private static final String ANSWER = "userHasAcceptedGDPR";

    private static volatile boolean logged;

    private Consent() {
    }

    // MRGSGDPR.showAgreementAtActivity(Activity, String, String)
    public static void showAgreementAtActivity(Object manager, Activity activity, String appId, String file) {
        if (!doit_consent("showAgreementAtActivity()", manager, activity)) {
            callMethod(manager, "showAgreementAtActivity", new Class<?>[] {Activity.class, String.class, String.class}, activity, appId, file);
        }
    }

    // MRGSGDPR.showDefaultAgreementAtActivity(Activity, String)
    public static void showDefaultAgreementAtActivity(Object manager, Activity activity, String file) {
        if (!doit_consent("showDefaultAgreementAtActivity()", manager, activity)) {
            callMethod(manager, "showDefaultAgreementAtActivity", new Class<?>[] {Activity.class, String.class}, activity, file);
        }
    }

    /**
     * Answers the call when a delegate is found, false when it is not: don't
     * interfere then, the SDK shows its window.
     */
    static boolean doit_consent(String called, Object manager, Activity activity) {
        Object param = getFakeDelegate(manager, activity);
        if (param == null) {
            return false;
        }
        if (!logged) {
            logged = true;
            Server.LOG.info("Consent window skipped, " + called + ": the game is told the user answered no");
        }
        if (findMethodExact(param.getClass(), ANSWER, boolean.class) == null) {
            return false;
        }
        notifyListener(param, activity, false);
        return true;
    }

    /**
     * Tells the delegate a second after the game's window is up, as
     * GameHelper's onStart reports a sign-in it did not try: the show call
     * comes in the middle of the game's onCreate, and Jungle Heat hands the
     * answer on to its native code, which is not started then and drops it.
     * Told at once where the activity has no window (the JVM's).
     * https://github.com/tvbarthel/ChaseWhisplyProject/blob/1d86fcc6b373e4a1d399a39868fd44187e119471/BaseGameUtils/src/main/java/com/google/example/games/basegameutils/GameHelper.java#L368-L393
     * https://github.com/tvbarthel/ChaseWhisplyProject/blob/1d86fcc6b373e4a1d399a39868fd44187e119471/BaseGameUtils/src/main/java/com/google/example/games/basegameutils/GameHelper.java#notifyListener
     * not ported: onStart (doit_consent is the caller, at the show call)
     * not ported: debugLog, mConnectOnStart, mGoogleApiClient, mSignInFailureReason, onSignInSucceeded, onSignInFailed (there is no client to connect, the answer is the delegate's own method)
     */
    static void notifyListener(final Object mListener, final Activity act, final boolean success) {
        Runnable run = new Runnable() {
            @Override
            public void run() {
                createAnswer(mListener, success);
            }
        };
        if (!postDelayed(act, run, 1000)) {
            run.run();
        }
    }

    /**
     * Runs r on the activity's main thread delayMillis after its window is
     * shown, as a Handler's postDelayed runs it after the time given. The
     * window is shown and not focused: Jungle Heat's splash is a window of
     * its own over the activity, it keeps the focus for as long as it loads.
     * the game's own Handler is not at hand, the activity's runOnUiThread is
     * called as Xposed's callMethod calls a method. False when the activity
     * cannot be waited for, nothing is posted then.
     * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/Handler.java#L491-L512
     * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/os/Handler.java#L705-L710
     * https://github.com/rovo89/XposedBridge/blob/master/src/de/robv/android/xposed/XposedHelpers.java#L965-L977
     * not ported: sendMessageDelayed, getPostMessage, sendMessageAtTime, SystemClock.uptimeMillis (no message queue, a thread sleeps the delay)
     * not ported: callMethod, findMethodBestMatch, IllegalAccessError, InvocationTargetError (the two methods are found once, exactly, and a failure is logged: the game goes on)
     */
    static boolean postDelayed(final Activity act, final Runnable r, final long delayMillis) {
        final Method getWindow = act == null ? null : findMethodExact(act.getClass(), "getWindow");
        final Method runOnUiThread = act == null ? null : findMethodExact(act.getClass(), "runOnUiThread", Runnable.class);
        if (getWindow == null || runOnUiThread == null) {
            return false;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // A minute at most: a window that is never shown still gets r.
                    for (int i = 0; i < 600 && !isShown(getWindow.invoke(act)); i++) {
                        Thread.sleep(100);
                    }
                    Thread.sleep(delayMillis);
                    runOnUiThread.invoke(act, r);
                } catch (InterruptedException | IllegalAccessException | IllegalArgumentException e) {
                    Server.LOG.warning("Consent answer not delivered: " + e);
                } catch (InvocationTargetException e) {
                    Server.LOG.warning("Consent answer not delivered: " + e.getCause());
                }
            }
        }, "GutapK Consent");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    /**
     * Whether the window's decor view is on screen: View.isShown() called on
     * Window.getDecorView(), false while the view is not made or attached yet.
     * https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/View.java#isShown
     * not ported: the walk up mParent and mViewFlags (fields of a View this code only reaches by reflection, isShown itself is called)
     */
    private static boolean isShown(Object window) throws IllegalAccessException, InvocationTargetException {
        Method getDecorView = window == null ? null : findMethodExact(window.getClass(), "getDecorView");
        Object current = getDecorView == null ? null : getDecorView.invoke(window);
        Method isShown = current == null ? null : findMethodExact(current.getClass(), "isShown");
        return isShown != null && Boolean.TRUE.equals(isShown.invoke(current));
    }

    /**
     * The delegate the game registered with the SDK: the one the SDK's
     * instance holds in a field, else the activity the window would have
     * opened over, when the activity is the delegate.
     */
    static Object getFakeDelegate(Object manager, Activity activity) {
        Field field = manager == null ? null : findFirstFieldByExactType(manager.getClass(), manager);
        if (field != null) {
            try {
                return field.get(manager);
            } catch (IllegalAccessException | RuntimeException e) {
                // The field is left, the activity is tried.
            }
        }
        return activity != null && findMethodExact(activity.getClass(), ANSWER, boolean.class) != null ? activity : null;
    }

    /**
     * The first field of the SDK's instance that holds a delegate: its type
     * has the answer method and it is set. The instance is itself a delegate
     * of its own result receiver, so it is not taken for the game's.
     * Null when there is none, where Xposed throws NoSuchFieldError.
     */
    static Field findFirstFieldByExactType(Class<?> clazz, Object manager) {
        Class<?> clz = clazz;
        do {
            for (Field field : clz.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || findMethodExact(field.getType(), ANSWER, boolean.class) == null) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    Object type = field.get(manager);
                    if (type != null && type != manager) {
                        return field;
                    }
                } catch (IllegalAccessException | RuntimeException e) {
                    // This field cannot be read, the next one may.
                }
            }
        } while ((clz = clz.getSuperclass()) != null && clz != Object.class);
        return null;
    }

    /**
     * A public method of a class, its name and parameter types given, the
     * ones inherited from a superclass or an interface included: getMethod
     * where Xposed has getDeclaredMethod, a delegate being often an inner
     * class that only implements the SDK's interface. Null where Xposed
     * throws NoSuchMethodError.
     * https://github.com/rovo89/XposedBridge/blob/master/src/de/robv/android/xposed/XposedHelpers.java#findMethodExact
     */
    static Method findMethodExact(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        try {
            Method method = clazz.getMethod(methodName, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** Tells the delegate the user's answer: false for no. */
    static boolean createAnswer(Object param, boolean connected) {
        Method method = findMethodExact(param.getClass(), ANSWER, boolean.class);
        if (method == null) {
            return false;
        }
        try {
            method.invoke(param, connected);
            return true;
        } catch (IllegalAccessException | IllegalArgumentException e) {
            return false;
        } catch (InvocationTargetException e) {
            // The game's own handler failed, as it would have when the SDK called it.
            throw rethrow(e.getCause());
        }
    }

    /** The SDK's own call on obj, for a game that registered no delegate. */
    static Object callMethod(Object obj, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            return findMethodExact(obj.getClass(), methodName, parameterTypes).invoke(obj, args);
        } catch (IllegalAccessException | RuntimeException e) {
            Server.LOG.warning("Consent window left to the SDK, " + methodName + " failed: " + e);
            return null;
        } catch (InvocationTargetException e) {
            throw rethrow(e.getCause());
        }
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof Error) {
            throw (Error) cause;
        }
        return cause instanceof RuntimeException ? (RuntimeException) cause : new IllegalStateException(cause);
    }
}
