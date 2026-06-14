#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>

NS_ASSUME_NONNULL_BEGIN

/// Pont Objective-C vers le SDK Zebra Link-OS iOS (`ZSDK_API`).
///
/// Le SDK Zebra est livré en **librairie statique + headers ObjC, SANS module Swift**
/// (contrairement à Star/Epson/Brother qui exposent un module) : `#if canImport` ne
/// peut donc PAS l'activer. Ce pont pilote les classes ObjC du SDK **uniquement via le
/// runtime** (`NSClassFromString`) : aucune référence de symbole au link, donc les apps
/// qui n'ajoutent PAS le SDK Zebra continuent de linker normalement, et le support Zebra
/// s'active automatiquement dès que `ZSDK_API` est présent dans la target App.
///
/// Les signatures suivent les headers réels du Link-OS Multiplatform SDK (iOS) :
/// `TcpPrinterConnection`, `MfiBtPrinterConnection`, `ZebraPrinterFactory`,
/// `ZebraPrinter`, `GraphicsUtil`, `PrinterStatus`.
@interface ZebraBridge : NSObject

/// `YES` si les classes Zebra (`ZebraPrinterFactory` + `TcpPrinterConnection`) sont
/// présentes dans le binaire (SDK ajouté à l'app).
+ (BOOL)isAvailable;

/// Ouvre une connexion TCP (port 9100 par défaut). Renvoie un jeton de connexion opaque
/// (l'objet `ZebraPrinterConnection`) ou `nil` + `error`.
+ (nullable id)connectTcp:(NSString *)host
                     port:(NSInteger)port
                timeoutMs:(NSInteger)timeoutMs
                    error:(NSError **)error;

/// Ouvre une connexion Bluetooth MFi via le numéro de série de l'accessoire.
+ (nullable id)connectBt:(NSString *)serialNumber error:(NSError **)error;

/// `YES` si la connexion est ouverte.
+ (BOOL)isOpen:(id)connection;

/// Ferme la connexion.
+ (void)disconnect:(id)connection;

/// Imprime un CGImage (rendu en ZPL par le SDK via GraphicsUtil), `copies` fois.
/// Renvoie `NO` + `error` en cas d'échec.
+ (BOOL)printImage:(id)connection
           cgImage:(CGImageRef)image
            copies:(NSInteger)copies
             error:(NSError **)error;

/// Lit le statut courant. Dictionnaire `{ready, headOpen, paperOut, paused}` (NSNumber
/// booléens) ou `nil` + `error`.
+ (nullable NSDictionary<NSString *, NSNumber *> *)status:(id)connection
                                                    error:(NSError **)error;

@end

NS_ASSUME_NONNULL_END
