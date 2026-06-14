#import "ZebraBridge.h"

// ⚠️ On ne référence AUCUN symbole de classe Zebra au link : tout passe par
// NSClassFromString. Ces protocoles ne servent qu'à donner au compilateur les bons
// encodages de types pour objc_msgSend (sélecteurs + types issus des headers réels du
// Link-OS SDK). Aucune classe déclarée ici → rien à résoudre au link pour une app sans
// le SDK Zebra.

static NSString *const ZebraBridgeErrorDomain = @"com.delicity.thermalprinter.zebra";

@protocol ZSDKConnection <NSObject>
- (BOOL)open;
- (void)close;
- (BOOL)isConnected;
- (void)setMaxTimeoutForRead:(NSInteger)paramMaxTimeoutForRead;
@end

@protocol ZSDKTcpConnection <ZSDKConnection>
- (instancetype)initWithAddress:(NSString *)anAddress andWithPort:(NSInteger)aPort;
@end

@protocol ZSDKBtConnection <ZSDKConnection>
- (instancetype)initWithSerialNumber:(NSString *)aSerialNumber;
@end

@protocol ZSDKGraphicsUtil <NSObject>
- (BOOL)printImage:(CGImageRef)image
               atX:(NSInteger)x
               atY:(NSInteger)y
         withWidth:(NSInteger)width
        withHeight:(NSInteger)height
 andIsInsideFormat:(BOOL)isInsideFormat
             error:(NSError **)error;
@end

@protocol ZSDKPrinter <NSObject>
- (nullable id<ZSDKGraphicsUtil>)getGraphicsUtil;
- (nullable id)getCurrentStatus:(NSError **)error; // PrinterStatus*
@end

// `getInstance:error:` est une méthode de CLASSE sur ZebraPrinterFactory. On la déclare
// en méthode d'instance et on l'envoie à l'objet Class (NSClassFromString) : objc_msgSend
// sur un objet classe résout la méthode de classe. Encodage de types correct via le proto.
@protocol ZSDKFactory <NSObject>
- (nullable id<ZSDKPrinter>)getInstance:(id<ZSDKConnection>)connection error:(NSError **)error;
@end

@implementation ZebraBridge

+ (NSError *)errorWithMessage:(NSString *)message code:(NSInteger)code {
    return [NSError errorWithDomain:ZebraBridgeErrorDomain
                               code:code
                           userInfo:@{NSLocalizedDescriptionKey: message}];
}

+ (BOOL)isAvailable {
    return NSClassFromString(@"ZebraPrinterFactory") != nil
        && (NSClassFromString(@"TcpPrinterConnection") != nil
            || NSClassFromString(@"MfiBtPrinterConnection") != nil);
}

+ (nullable id)connectTcp:(NSString *)host
                     port:(NSInteger)port
                timeoutMs:(NSInteger)timeoutMs
                    error:(NSError **)error {
    Class cls = NSClassFromString(@"TcpPrinterConnection");
    if (cls == nil) {
        if (error) *error = [self errorWithMessage:@"SDK Zebra Link-OS absent" code:1];
        return nil;
    }
    id<ZSDKTcpConnection> conn = [(id<ZSDKTcpConnection>)[cls alloc] initWithAddress:host andWithPort:port];
    if (conn == nil) {
        if (error) *error = [self errorWithMessage:@"Init connexion Zebra échouée" code:2];
        return nil;
    }
    if (timeoutMs > 0) {
        [conn setMaxTimeoutForRead:timeoutMs];
    }
    if (![conn open]) {
        if (error) *error = [self errorWithMessage:[NSString stringWithFormat:@"Connexion Zebra échouée: %@", host] code:3];
        return nil;
    }
    return conn;
}

+ (nullable id)connectBt:(NSString *)serialNumber error:(NSError **)error {
    Class cls = NSClassFromString(@"MfiBtPrinterConnection");
    if (cls == nil) {
        if (error) *error = [self errorWithMessage:@"SDK Zebra Link-OS (Bluetooth MFi) absent" code:1];
        return nil;
    }
    id<ZSDKBtConnection> conn = [(id<ZSDKBtConnection>)[cls alloc] initWithSerialNumber:serialNumber];
    if (conn == nil) {
        if (error) *error = [self errorWithMessage:@"Init connexion Bluetooth Zebra échouée" code:2];
        return nil;
    }
    if (![conn open]) {
        if (error) *error = [self errorWithMessage:[NSString stringWithFormat:@"Connexion Bluetooth Zebra échouée: %@", serialNumber] code:3];
        return nil;
    }
    return conn;
}

+ (BOOL)isOpen:(id)connection {
    if (connection == nil) return NO;
    return [(id<ZSDKConnection>)connection isConnected];
}

+ (void)disconnect:(id)connection {
    [(id<ZSDKConnection>)connection close];
}

+ (nullable id<ZSDKPrinter>)printerForConnection:(id)connection error:(NSError **)error {
    Class factoryCls = NSClassFromString(@"ZebraPrinterFactory");
    if (factoryCls == nil) {
        if (error) *error = [self errorWithMessage:@"SDK Zebra Link-OS absent" code:1];
        return nil;
    }
    NSError *e = nil;
    id<ZSDKPrinter> printer = [(id<ZSDKFactory>)factoryCls getInstance:(id<ZSDKConnection>)connection error:&e];
    if (printer == nil && error) {
        *error = e ?: [self errorWithMessage:@"ZebraPrinterFactory.getInstance a échoué" code:4];
    }
    return printer;
}

+ (BOOL)printImage:(id)connection
           cgImage:(CGImageRef)image
            copies:(NSInteger)copies
             error:(NSError **)error {
    if (connection == nil) {
        if (error) *error = [self errorWithMessage:@"Zebra non connecté" code:5];
        return NO;
    }
    id<ZSDKPrinter> printer = [self printerForConnection:connection error:error];
    if (printer == nil) return NO;
    id<ZSDKGraphicsUtil> graphics = [printer getGraphicsUtil];
    if (graphics == nil) {
        if (error) *error = [self errorWithMessage:@"GraphicsUtil indisponible" code:6];
        return NO;
    }
    NSInteger width = (NSInteger)CGImageGetWidth(image);
    NSInteger height = (NSInteger)CGImageGetHeight(image);
    NSInteger n = MAX((NSInteger)1, copies);
    for (NSInteger i = 0; i < n; i++) {
        NSError *pe = nil;
        BOOL ok = [graphics printImage:image
                                   atX:0
                                   atY:0
                             withWidth:width
                            withHeight:height
                     andIsInsideFormat:NO
                                 error:&pe];
        if (!ok) {
            if (error) *error = pe ?: [self errorWithMessage:@"Impression Zebra échouée" code:7];
            return NO;
        }
    }
    return YES;
}

+ (nullable NSDictionary<NSString *, NSNumber *> *)status:(id)connection error:(NSError **)error {
    id<ZSDKPrinter> printer = [self printerForConnection:connection error:error];
    if (printer == nil) return nil;
    NSError *se = nil;
    id status = [printer getCurrentStatus:&se]; // PrinterStatus*
    if (status == nil) {
        if (error) *error = se ?: [self errorWithMessage:@"getCurrentStatus a échoué" code:8];
        return nil;
    }
    // Lecture des propriétés BOOL via KVC (PrinterStatus expose isReadyToPrint, etc.).
    BOOL (^flag)(NSString *) = ^BOOL(NSString *key) {
        id v = [status valueForKey:key];
        return [v respondsToSelector:@selector(boolValue)] ? [v boolValue] : NO;
    };
    return @{
        @"ready": @(flag(@"isReadyToPrint")),
        @"headOpen": @(flag(@"isHeadOpen")),
        @"paperOut": @(flag(@"isPaperOut")),
        @"paused": @(flag(@"isPaused")),
    };
}

@end
