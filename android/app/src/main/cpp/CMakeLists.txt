cmake_minimum_required(VERSION 3.10.2)
project("aydtv_crypto")

add_library(
    aydtv_crypto
    SHARED
    hand_garble.c
    modified_md5.c
    omg_hax.c
    playfair.c
    sap_hash.c
    airplay_crypto_jni.c
)

find_library(log-lib log)

target_link_libraries(
    aydtv_crypto
    ${log-lib}
)
