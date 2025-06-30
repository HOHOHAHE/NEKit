package com.example.nekit.Config

open class ConfigurationException(message: String) : Exception(message) {
    open class RuleParsingException(message: String) : ConfigurationException(message)
    open class AdapterParsingException(message: String) : ConfigurationException(message)
    class AdapterIDMissingException(message: String) : AdapterParsingException(message)
    class AdapterTypeMissingException(message: String) : AdapterParsingException(message)
    class UnknownAdapterTypeException(message: String) : AdapterParsingException(message)
    class InvalidYamlFileException(message: String) : ConfigurationException(message)
    class NoAdapterDefinedException(message: String) : ConfigurationException(message)
    class RuleTypeMissingException(message: String) : RuleParsingException(message)
    class UnknownRuleTypeException(message: String) : RuleParsingException(message)
}
