package dev.jacobandersen.bastion.post.syndication

import dev.jacobandersen.bastion.post.internal.syndication.SyndicationConfig
import org.springframework.stereotype.Service

data class SyndicationTargetView(
    val uid: String,
    val name: String,
)

@Service
class SyndicationTargetQueryService(
    private val config: SyndicationConfig,
) {
    fun targets(): List<SyndicationTargetView> =
        config.targets.filterNot { it.auto }.map { target ->
            SyndicationTargetView(uid = target.uid, name = target.name)
        }
}
