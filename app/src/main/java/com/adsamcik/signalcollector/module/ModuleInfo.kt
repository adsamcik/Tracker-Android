package com.adsamcik.signalcollector.module

data class ModuleInfo(val module: Module, var shouldBeInstalled: Boolean = false, var isInstalled: Boolean = false)