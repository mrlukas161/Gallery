package org.fossify.gallery.family

// D8: rozhodnutie o návrhu premenovania (súbežná zmena mena na oboch telefónoch) sa zapíše ako nová operácia
// PersonRename. Dostane vyšší lamport než obe súbežné zmeny, takže na druhom telefóne vyhrá (posledná zmena
// vyhráva) a obe strany skončia s rovnakým menom — aj keď „prevziať“ zvolia obe naraz (výsledok určí
// poradie (lamport, deviceId), nie kto rozhodol skôr). Čistá logika (NameConvergenceTest).
object RenameResolution {
    // „Prevziať“: moje meno sa mení na ich
    fun onAccept(p: RenameProposal): PersonRename = PersonRename(p.uuid, p.theirName, p.myName)

    // „Ponechať“: moje meno ostáva a potvrdí sa ako novšie (ich telefón ho prevezme potichu, lebo previousName
    // je jeho aktuálne meno). null = nie je čo potvrdiť (tu osoba meno nemá).
    fun onKeep(p: RenameProposal): PersonRename? =
        p.myName?.takeIf { it.isNotBlank() }?.let { PersonRename(p.uuid, it, p.theirName) }
}
