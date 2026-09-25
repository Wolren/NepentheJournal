#ifndef NEPENTHE_CCGCM_SPI_H
#define NEPENTHE_CCGCM_SPI_H
#include <CommonCrypto/CommonCryptor.h>

/* Bindings for the CommonCrypto AES-GCM oneshot SPI (implemented in
 * libSystem, declared here because CommonCryptorSPI.h is not shipped in
 * the public SDK). Signatures mirror Apple's CommonCryptorSPI.h. */

CCCryptorStatus CCCryptorGCMOneshotEncrypt(CCAlgorithm alg, const void *key, size_t keyLength,
                                           const void *iv, size_t ivLength,
                                           const void *aData, size_t aDataLength,
                                           const void *dataIn, size_t dataInLength,
                                           void *cipherOut, void *tagOut, size_t tagLength);

CCCryptorStatus CCCryptorGCMOneshotDecrypt(CCAlgorithm alg, const void *key, size_t keyLength,
                                           const void *iv, size_t ivLen,
                                           const void *aData, size_t aDataLen,
                                           const void *dataIn, size_t dataInLength,
                                           void *dataOut, const void *tagIn, size_t tagLength);

#endif
