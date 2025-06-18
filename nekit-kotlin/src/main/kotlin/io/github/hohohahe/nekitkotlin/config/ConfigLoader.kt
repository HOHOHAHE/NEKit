package io.github.hohohahe.nekitkotlin.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.KotlinModule
import java.io.File
import java.io.InputStream

object ConfigLoader {

    private val objectMapper: ObjectMapper by lazy {
        ObjectMapper(YAMLFactory()).registerModule(
            KotlinModule.Builder()
                .withReflectionCacheSize(512)
                .configure(KotlinFeature.NullToEmptyCollection, false)
                .configure(KotlinFeature.NullToEmptyMap, false)
                .configure(KotlinFeature.NullIsSameAsDefault, false)
                .configure(KotlinFeature.SingletonSupport, false)
                .configure(KotlinFeature.StrictNullChecks, false)
                .build()
        )
    }

    fun loadFromFile(filePath: String): Configuration {
        val file = File(filePath)
        if (!file.exists()) {
            throw ConfigException("Configuration file not found: ${'$'}filePath")
        }
        return objectMapper.readValue(file, Configuration::class.java)
    }

    fun loadFromInputStream(inputStream: InputStream): Configuration {
        return objectMapper.readValue(inputStream, Configuration::class.java)
    }
}

class ConfigException(message: String) : RuntimeException(message)
