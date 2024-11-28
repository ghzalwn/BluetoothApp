package com.bluetoothapp

import android.view.View
import com.facebook.react.ReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.ReactShadowNode
import com.facebook.react.uimanager.ViewManager

class BLEPackage : ReactPackage{
    override fun createNativeModules(context: ReactApplicationContext): List<NativeModule> {
        return listOf(BLEModule(context))
    }

    override fun createViewManagers(reactContext: ReactApplicationContext): List<ViewManager<*, *>> {
        return emptyList()
    }

}