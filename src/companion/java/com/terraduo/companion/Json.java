package com.terraduo.companion;

import org.json.JSONException;
import org.json.JSONObject;

/** Small JSON helpers shared by the panel and the sprite store. */
final class Json {
    private Json() {}

    /** Like {@link JSONObject#put} without the checked exception (keys here are fixed strings, so failure is a bug). */
    static void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (JSONException error) {
            throw new IllegalArgumentException(key, error);
        }
    }
}
