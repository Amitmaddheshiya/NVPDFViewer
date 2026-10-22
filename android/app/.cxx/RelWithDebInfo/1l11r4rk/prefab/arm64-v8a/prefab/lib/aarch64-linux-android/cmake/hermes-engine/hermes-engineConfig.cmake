if(NOT TARGET hermes-engine::hermesvm)
add_library(hermes-engine::hermesvm SHARED IMPORTED)
set_target_properties(hermes-engine::hermesvm PROPERTIES
    IMPORTED_LOCATION "C:/Users/Sandman/.gradle/caches/9.3.1/transforms/4843332f476e7a594600628ab39596c3/workspace/transformed/jetified-hermes-android-250829098.0.14-release/prefab/modules/hermesvm/libs/android.arm64-v8a/libhermesvm.so"
    INTERFACE_INCLUDE_DIRECTORIES "C:/Users/Sandman/.gradle/caches/9.3.1/transforms/4843332f476e7a594600628ab39596c3/workspace/transformed/jetified-hermes-android-250829098.0.14-release/prefab/modules/hermesvm/include"
    INTERFACE_LINK_LIBRARIES ""
)
endif()

