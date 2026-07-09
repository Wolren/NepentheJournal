package app.journal.data
// iOS actual: uses Couchbase Lite Objective-C SDK via Kotlin/ObjC interop.
// Add CouchbaseLite XCFramework via CocoaPods (kotlin.cocoapods block in build.gradle.kts).
// TLSIdentity stored in iOS Keychain (kSecAttrLabel-tagged SecCertificate).
// See kotbase.dev/current/platforms/ for XCFramework version matching.
actual class DatabaseProvider actual constructor(private val vaultName: String) {
    actual fun open() {
        // TODO: CBLDatabase(name: vaultName, config: CBLDatabaseConfiguration(), error: &error)
        // Then create collections via CBLDatabase.createCollectionWithName:scope:error:
    }
    actual fun close() {
        // TODO: db?.close(nil)
    }
}
