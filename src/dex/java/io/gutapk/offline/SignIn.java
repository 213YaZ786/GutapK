package io.gutapk.offline;

/*
 * The Google Play Games sign-in a game starts with its activity. The game
 * carries a copy of BaseGameUtils' GameHelper whose onStart connects to Google
 * when mConnectOnStart is true, which it is until told otherwise, and Google
 * then opens its "Sign in with ease" screen at every start. An offline build
 * turns the game's call to that onStart into a call to this one, which does
 * what Google documents to stop the automatic sign-in: setConnectOnStart(false)
 * first, then the helper's own onStart. The helper keeps working, it reports a
 * sign-in failure to its listener as when the user declines. The game's later
 * calls to beginUserInitiatedSignIn, its own at startup and its buttons', get
 * the same failure: Google Play Games needs a network the offline build does
 * not count on, and its screen is never shown.
 *
 * The helper is the game's class, which the server's dex does not know, so
 * both methods are called by reflection, with the steps Consent takes from
 * Xposed's helpers. When setConnectOnStart cannot be found or called the
 * helper's onStart is still called, so the game never hangs, and the game's
 * own exception is thrown as it is.
 *
 * https://github.com/tvbarthel/ChaseWhisplyProject/blob/1d86fcc6b373e4a1d399a39868fd44187e119471/BaseGameUtils/src/main/java/com/google/example/games/basegameutils/GameHelper.java#setConnectOnStart
 * https://github.com/tvbarthel/ChaseWhisplyProject/blob/1d86fcc6b373e4a1d399a39868fd44187e119471/BaseGameUtils/src/main/java/com/google/example/games/basegameutils/GameHelper.java#onStart
 * https://github.com/rovo89/XposedBridge/blob/master/src/de/robv/android/xposed/XposedHelpers.java#findMethodExact
 * https://github.com/rovo89/XposedBridge/blob/master/src/de/robv/android/xposed/XposedHelpers.java#callMethod
 *
 * Not ported: the body of onStart (mActivity, mAppContext, assertConfigured, the connect and the delayed notifyListener: the helper's own onStart does them, it is called below)
 * Not ported: debugLog (one line goes to Server.LOG)
 * Not ported: fieldCache, methodCache, getParametersString, findMethodBestMatch, InvocationTargetError, XposedBridge.log (as in Consent: a call is made once per launch, nothing is cached or matched by best fit)
 *
 * Copyright (C) 2013 Google Inc., Apache License 2.0. Copyright 2013 rovo89 and Tungstwenty, Apache License 2.0.
 */

import android.app.Activity;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public final class SignIn {

    private static volatile boolean logged;

    private static volatile boolean told;

    /**
     * The helpers whose mConnectOnStart is turned off already: once is
     * enough, only beginUserInitiatedSignIn sets it back and that call is
     * answered here.
     */
    private static final Set<Object> mConnectOnStart = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<Object, Boolean>()));

    private SignIn() {
    }

    // GameHelper.onStart(Activity)
    public static void onStart(Object helper, Activity act) {
        if (mConnectOnStart.add(helper) && setConnectOnStart(helper, false) && !logged) {
            logged = true;
            Server.LOG.info("Automatic sign-in skipped: " + helper.getClass().getName() + ".onStart()");
        }
        callMethod(helper, "onStart", new Class<?>[] {Activity.class}, act);
    }

    /**
     * GameHelper.beginUserInitiatedSignIn(), answered as a sign-in the user
     * declined: the helper's own notifyListener(false), which tells the game's
     * listener onSignInFailed, and Google is never asked. Jungle Heat's native
     * code calls it by itself a second after the start, so turning the start's
     * connect off is not enough to keep the sign-in screen away. A helper with
     * no notifyListener gets the original call.
     * https://github.com/tvbarthel/ChaseWhisplyProject/blob/1d86fcc6b373e4a1d399a39868fd44187e119471/BaseGameUtils/src/main/java/com/google/example/games/basegameutils/GameHelper.java#beginUserInitiatedSignIn
     * https://github.com/tvbarthel/ChaseWhisplyProject/blob/1d86fcc6b373e4a1d399a39868fd44187e119471/BaseGameUtils/src/main/java/com/google/example/games/basegameutils/GameHelper.java#notifyListener
     * https://github.com/rovo89/XposedBridge/blob/master/src/de/robv/android/xposed/XposedHelpers.java#findMethodExact
     * not ported: notifyListener (the helper's own is called, by reflection, it is package private)
     * not ported: debugLog, logWarn, resetSignInCancellations, mSignInCancelled, mConnectOnStart, mUserInitiatedSignIn, mConnectionResult, resolveConnectionResult, connect (no sign-in is tried)
     * not ported: mGoogleApiClient.isConnected and notifyListener(true) (a helper never connects in this build, the answer is always no)
     */
    public static void beginUserInitiatedSignIn(Object helper) {
        Method notifyListener = null;
        Class<?> clazz = helper.getClass();
        do {
            try {
                notifyListener = clazz.getDeclaredMethod("notifyListener", boolean.class);
                notifyListener.setAccessible(true);
            } catch (NoSuchMethodException | RuntimeException e) {
                notifyListener = null;
            }
        } while (notifyListener == null && (clazz = clazz.getSuperclass()) != null && clazz != Object.class);
        if (notifyListener == null) {
            Server.LOG.warning("Sign-in left to the game, no notifyListener in " + helper.getClass().getName());
            callMethod(helper, "beginUserInitiatedSignIn", new Class<?>[0]);
            return;
        }
        if (!told) {
            told = true;
            Server.LOG.info("Sign-in skipped: " + helper.getClass().getName() + ".beginUserInitiatedSignIn(), the game is told the user declined");
        }
        try {
            notifyListener.invoke(helper, false);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            Server.LOG.warning("Sign-in left to the game, notifyListener failed: " + e);
            callMethod(helper, "beginUserInitiatedSignIn", new Class<?>[0]);
        } catch (InvocationTargetException e) {
            // The game's own listener failed, as it would have when Google answered.
            throw rethrow(e.getCause());
        }
    }

    /**
     * GameHelper.setConnectOnStart(boolean), called on the game's copy of it.
     * False when it cannot be found or called, where the helper would have
     * connected: the caller goes on with the helper's own onStart.
     */
    static boolean setConnectOnStart(Object helper, boolean connectOnStart) {
        Method method = findMethodExact(helper.getClass(), "setConnectOnStart", boolean.class);
        if (method == null) {
            Server.LOG.warning("Automatic sign-in left to the game, no setConnectOnStart in " + helper.getClass().getName());
            return false;
        }
        try {
            method.invoke(helper, connectOnStart);
            return true;
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
            Server.LOG.warning("Automatic sign-in left to the game, setConnectOnStart failed: " + e);
            return false;
        }
    }

    /**
     * A public method of a class, its name and parameter types given, the
     * ones inherited included. Null where Xposed throws NoSuchMethodError.
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

    /**
     * The helper's own call on obj, as the game made it.
     * https://github.com/rovo89/XposedBridge/blob/master/src/de/robv/android/xposed/XposedHelpers.java#callMethod
     */
    static Object callMethod(Object obj, String methodName, Class<?>[] parameterTypes, Object... args) {
        Method method = findMethodExact(obj.getClass(), methodName, parameterTypes);
        if (method == null) {
            Server.LOG.warning("Automatic sign-in left to the game, no " + methodName + " in " + obj.getClass().getName());
            return null;
        }
        try {
            return method.invoke(obj, args);
        } catch (IllegalAccessException | IllegalArgumentException e) {
            Server.LOG.warning("Automatic sign-in left to the game, " + methodName + " failed: " + e);
            return null;
        } catch (InvocationTargetException e) {
            // The game's own onStart failed, as it would have without this call.
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
