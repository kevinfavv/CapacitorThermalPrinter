#import <Foundation/Foundation.h>
#import <Capacitor/Capacitor.h>

// Enregistrement du plugin auprès du runtime Capacitor (Objective-C bridge).
// Doit refléter les méthodes déclarées dans pluginMethods (ThermalPrinterPlugin.swift).
CAP_PLUGIN(ThermalPrinterPlugin, "ThermalPrinter",
    CAP_PLUGIN_METHOD(discoverPrinters, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(connectPrinter, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(disconnectPrinter, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(setDefaultPrinter, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(getDefaultPrinter, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(getSavedPrinters, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(removePrinter, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(printImage, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(getPrinterStatus, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(requestPermissions, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(checkPermissions, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(startStatusMonitor, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(stopStatusMonitor, CAPPluginReturnPromise);
    CAP_PLUGIN_METHOD(getDebugLog, CAPPluginReturnPromise);
)
