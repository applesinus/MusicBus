package com.musicbus.mappings;

public enum McFeature {
    PLAY_RETURNS_RESULT("playReturnsResult"),
    HAS_PLAY_RESULT("hasPlayResult"),
    SOUND_INSTANCE_GET_IDENTIFIER("soundInstanceGetIdentifier"),
    RESOURCE_ID_IS_IDENTIFIER("resourceIdIsIdentifier"),
    RESOURCE_PROVIDER_OPEN("resourceProviderOpen"),
    HAS_RESOURCE_PROVIDER("hasResourceProvider"),
    SOUND_OPTIONS_IN_OPTIONS_PKG("soundOptionsInOptionsPkg"),
    SOUND_OPTIONS_ADD_OPTIONS("soundOptionsAddOptions"),
    HAS_OPTION_INSTANCE("hasOptionInstance"),
    OPTIONS_LIST_ADD_SMALL_ARRAY("optionsListAddSmallArray"),
    HAS_INTENTIONALLY_EMPTY_SOUND("hasIntentionallyEmptySound"),
    SOUND_ENGINE_PAUSE_ALL_EXCEPT("soundEnginePauseAllExcept"),
    SOUND_ENGINE_REFRESH_CATEGORY_VOLUME("soundEngineRefreshCategoryVolume"),
    SOUND_ENGINE_UPDATE_CATEGORY_VOLUME_HAS_FLOAT("soundEngineUpdateCategoryVolumeHasFloat"),
    SOUND_ENGINE_CALCULATE_VOLUME_FLOAT_SOURCE("soundEngineCalculateVolumeFloatSource"),
    SOUND_BUFFER_GET_STREAM_HAS_FLAG("soundBufferGetStreamHasFlag"),
    OPTION_INSTANCE_USES_VALUE_UPDATE_LISTENER("optionInstanceUsesValueUpdateListener");

    private final String jsonKey;

    McFeature(String jsonKey) {
        this.jsonKey = jsonKey;
    }

    public String getJsonKey() {
        return jsonKey;
    }
}