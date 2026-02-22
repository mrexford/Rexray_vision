package com.example.rexray_vision

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.TypeAdapter
import com.google.gson.TypeAdapterFactory
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonWriter

class MessageTypeAdapterFactory : TypeAdapterFactory {
    override fun <T : Any?> create(gson: Gson, type: TypeToken<T>): TypeAdapter<T>? {
        if (!NetworkService.Message::class.java.isAssignableFrom(type.rawType)) {
            return null
        }

        val delegate = gson.getDelegateAdapter(this, type)

        return object : TypeAdapter<T>() {
            override fun write(out: JsonWriter, value: T) {
                val jsonObject = delegate.toJsonTree(value).asJsonObject
                jsonObject.addProperty("type", value!!::class.java.simpleName)
                gson.toJson(jsonObject, out)
            }

            override fun read(`in`: JsonReader): T? {
                val jsonObject = gson.fromJson<JsonElement>(`in`, JsonElement::class.java).asJsonObject
                val typeName = jsonObject.get("type").asString

                val messageClass = when (typeName) {
                    "SetParams" -> NetworkService.Message.SetParams::class.java
                    "ArmCapture" -> NetworkService.Message.ArmCapture::class.java
                    "DisarmCapture" -> NetworkService.Message.DisarmCapture::class.java
                    "StartCapture" -> NetworkService.Message.StartCapture::class.java
                    "StopCapture" -> NetworkService.Message.StopCapture::class.java
                    "StatusUpdate" -> NetworkService.Message.StatusUpdate::class.java
                    "UpdateCameraName" -> NetworkService.Message.UpdateCameraName::class.java
                    "JoinGroup" -> NetworkService.Message.JoinGroup::class.java
                    "LeaveGroup" -> NetworkService.Message.LeaveGroup::class.java
                    "CommandAck" -> NetworkService.Message.CommandAck::class.java
                    "ConnectionRejected" -> NetworkService.Message.ConnectionRejected::class.java
                    else -> throw IllegalArgumentException("Unknown message type: $typeName")
                }

                val delegateAdapter = gson.getDelegateAdapter(this@MessageTypeAdapterFactory, TypeToken.get(messageClass))
                return delegateAdapter.fromJsonTree(jsonObject) as? T
            }
        }
    }
}