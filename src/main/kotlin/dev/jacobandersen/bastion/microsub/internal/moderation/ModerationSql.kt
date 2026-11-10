package dev.jacobandersen.bastion.microsub.internal.moderation

/** SQL fragments shared by every query that must agree on which entries count as visible. */
object ModerationSql {
    /** Entries (alias `e`) whose author is not muted or blocked, globally or in the entry's channel. */
    const val NOT_MODERATED =
        """not exists (
                select 1 from microsub_moderation m
                where m.url = e.author_url and (m.channel_id = e.channel_id or m.channel_id is null)
            )"""
}
