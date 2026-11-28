package app.sonar.power

internal fun shouldConserve(
    batterySaver: Boolean,
    dataSaver: Boolean,
    lowBattery: Boolean,
    cellular: Boolean,
): Boolean = (batterySaver && lowBattery) || (dataSaver && cellular)
