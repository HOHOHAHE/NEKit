package Config

import java.io.IOException

// --- ConfigurationException Definitions ---
sealed class ConfigurationException(message: String) : Exception(message) {
    class InvalidYamlFileException(message: String = "Invalid YAML file content.") : ConfigurationException(message)
    class NoRuleDefinedException(message: String = "No rule defined in configuration.") : ConfigurationException(message)
    class RuleTypeMissingException(message: String = "Rule type is missing.") : ConfigurationException(message)
    class UnknownRuleTypeException(typeName: String) : ConfigurationException("Unknown rule type: $typeName")
    class RuleParsingException(errorInfo: String) : ConfigurationException("Rule parsing error: $errorInfo")
    class NoAdapterDefinedException(message: String = "No adapter defined in configuration.") : ConfigurationException(message)
    class AdapterIDMissingException(message: String = "Adapter ID is missing.") : ConfigurationException(message)
    class AdapterTypeMissingException(message: String = "Adapter type is missing.") : ConfigurationException(message)
    class UnknownAdapterTypeException(typeName: String) : ConfigurationException("Unknown adapter type: $typeName")
    class AdapterParsingException(errorInfo: String) : ConfigurationException("Adapter parsing error: $errorInfo")
}
