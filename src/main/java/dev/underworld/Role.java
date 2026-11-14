package dev.underworld;

/** Friendly Citizens NPCs: quest givers and merchants. */
public enum Role {
    HERMIT("Brother Ash", false,
            "Few fall this deep and keep breathing. Sit. Listen. Vael remembers its dead."),
    SEEKER("Lyra the Shard Seeker", false,
            "Hear that hum? The crystals are still singing. Something in the citadel is calling them."),
    CARTOGRAPHER("Orin the Cartographer", false,
            "The land cracks a little more each day. I draw what is left before it is gone."),
    HUNTER("Skarn the Frostbound", false,
            "Snow hides everything. Wolves, graves, and the men who never came home."),
    SEER("Veyla the Frost Seer", false,
            "The standing stones whisper in a tongue older than the kingdom. Come closer."),
    GRAVEKEEPER("Mald the Gravekeeper", false,
            "Down in the canyon the dead are many and the living are few. Mind the sculk above."),
    HERALD("Herald Maren", false,
            "I still announce the queen to empty streets. Someone has to remember her name."),
    ARCHIVIST("The Citadel Archivist", true,
            "Every page of Vael passes through my hands. Some I sell, if the price is fair."),
    MERCHANT("Void Merchant", true,
            "Coins, relics, and secrets. What does a survivor need today?");

    public final String display;
    public final boolean shop;
    public final String greeting;

    Role(String display, boolean shop, String greeting) {
        this.display = display;
        this.shop = shop;
        this.greeting = greeting;
    }

    public boolean questGiver() {
        return !Quests.byGiver(this).isEmpty();
    }

    /** Name tag colour: gold for quest givers, aqua for pure merchants. */
    public String coloredName() {
        return (this == MERCHANT || this == ARCHIVIST ? "\u00a7b" : "\u00a76") + display;
    }
}
