#ifndef NEPENTHE_SECKEY_EXCHANGE_H
#define NEPENTHE_SECKEY_EXCHANGE_H
#include <Security/SecKey.h>

/* Kotlin's platform.Security klib does not expose SecKeyCopyKeyExchangeResult
 * (SecKey.h on the iOS SDK does not yield it to cinterop). Redeclare it here
 * with the exact SDK signature; identical redeclaration is valid C when the
 * header does provide it. */
CFDataRef _Nullable SecKeyCopyKeyExchangeResult(SecKeyRef privateKey,
                                                 SecKeyAlgorithm algorithm,
                                                 SecKeyRef publicKey,
                                                 CFDictionaryRef parameters,
                                                 CFErrorRef *error);

#endif
