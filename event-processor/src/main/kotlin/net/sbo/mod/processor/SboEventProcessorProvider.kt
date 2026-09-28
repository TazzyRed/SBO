package net.sbo.mod.processor

import com.google.devtools.ksp.processing.*

class SboEventProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return SboEventProcessor(
            codeGenerator = environment.codeGenerator,
            logger = environment.logger
        )
    }
}
