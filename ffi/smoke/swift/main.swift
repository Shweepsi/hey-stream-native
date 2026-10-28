// Vérifie que les liaisons Swift générées compilent, chargent la bibliothèque
// native et renvoient des erreurs typées. Lancé par la CI sur macOS.
import Foundation

func run() throws {
    print("cœur \(coreVersion())")
    let dir = FileManager.default.temporaryDirectory
        .appendingPathComponent("hs-swift-\(ProcessInfo.processInfo.processIdentifier)").path
    try FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)

    // Serveur injoignable : chargement, cache et erreurs typées.
    let core = try HeyStreamCore(serverUrl: "http://127.0.0.1:9", username: "u", password: "p", dataDir: dir)
    let categories = try core.categories(section: .live)
    precondition(categories.isEmpty)

    do {
        _ = try core.authenticate()
        preconditionFailure("le serveur est injoignable")
    } catch HeyStreamError.Network {}

    do {
        try core.setPin(newPin: "12a4", current: nil)
        preconditionFailure("code invalide accepté")
    } catch HeyStreamError.InvalidPin {}

    try core.setPin(newPin: "1234", current: nil)
    try core.unlockParental(pin: "1234")
    precondition(core.isParentalUnlocked())
    precondition(core.streamUrl(kind: .live, id: 5, extension: nil) == "http://127.0.0.1:9/live/u/p/5.ts")
    // `precondition` n'accepte pas `try` : appels sortis de la condition.
    let liveUrl = try core.liveUrl(streamId: 5, preferred: .hls)
    precondition(liveUrl == "http://127.0.0.1:9/live/u/p/5.m3u8")
    let account = try core.accountInfo()
    precondition(account == nil)

    try core.signOut()
    do {
        _ = try core.categories(section: .live)
        preconditionFailure("objet utilisable après la déconnexion")
    } catch HeyStreamError.SignedOut {}
    print("liaisons Swift : OK")
}

do {
    try run()
} catch {
    fatalError("échec : \(error)")
}
