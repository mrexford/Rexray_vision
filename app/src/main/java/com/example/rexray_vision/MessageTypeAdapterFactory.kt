package com.example.rexray_vision

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter
import java.io.IOException

class MessageTypeAdapterFactory : TypeAdapterFactory {
    override fun <T : Any?> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        if (type.rawType != NetworkManager.Message::class.java) {
            return null
        }

        val delegateAdapter = gson.getDelegateAdapter(this, type)
        val elementAdapter = gson.getAdapter(JsonElement::class.java)

        return object : TypeAdapter<T>() {
            override fun write(out: JsonWriter, value: T) {
                val jsonObject = delegateAdapter.toJsonTree(value).asJsonObject
                jsonObject.addProperty("type", value!!::class.java.simpleName)
                elementAdapter.write(out, jsonObject)
            }

            override fun read(`in`: JsonReader): T {
                val jsonObject = elementAdapter.read(`in`).asJsonObject
                val typeName = jsonObject.remove("type").asString
                val actualType = when (typeName) {
                    "SetParams" -> NetworkManager.Message.SetParams::class.java
                    "ArmCapture" -> NetworkManager.Message.ArmCapture::class.java
                    "StartCapture" -> NetworkManager.Message.StartCapture::class.java
                    "StatusUpdate" -> NetworkManager.Message.StatusUpdate::class.java
                    "JoinGroup" -> NetworkManager.Message.JoinGroup::class.java
                    else -> throw IOException("Unknown type: $typeName")
                }
                return gson.fromJson(jsonObject, actualType) as T
            }
        }.nullSafe()
    }
}
