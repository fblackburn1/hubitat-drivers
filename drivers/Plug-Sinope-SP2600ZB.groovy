/* groovylint-disable DuplicateNumberLiteral */
/* groovylint-disable UnnecessaryGetter */
/**
 *  Sinope Zigbee Plug (SP2600ZB) Device Driver for Hubitat
 *
 *  1.0 (2022-12-18): initial release
 *  Author: fblackburn
 *  Inspired by:
 *    - Sinope => https://github.com/SmartThingsCommunity/SmartThingsPublic/tree/master/devicetypes/sinope-technologies
 *    - sacua => https://github.com/sacua/SinopeDriverHubitat/blob/main/drivers/SP2600ZB_Sinope_Hubitat.groovy
 */

import hubitat.device.HubMultiAction

metadata
{
    definition(
        name: 'Sinope Zigbee Plug (SP2600ZB)',
        namespace: 'fblackburn',
        author: 'fblackburn',
    ) {
        capability 'Switch'
        capability 'Configuration'
        capability 'Refresh'
        capability 'Outlet'
        capability 'PowerMeter'
        capability 'EnergyMeter'
        capability 'Flash'
    }
    preferences {
        input(
            name: 'powerChange',
            type: 'number',
            title: 'Power Change',
            description: 'Difference of watts to trigger power report (1..10000)',
            range: '1..10000',
            defaultValue: getDefaultPowerChange()
        )
        input(
            name: 'trace',
            type: 'bool',
            title: 'Enable debug logging',
            defaultValue: false
        )
    }
}

void installed() {
    if (settings.trace) {
        log.trace 'SP2600ZB >> installed()'
    }
    configure()
    refresh()
}

void updated() {
    if (settings.trace) {
        log.trace 'SP2600ZB >> updated()'
    }
    configure()
    refresh()
}

void uninstalled() {
    if (settings.trace) {
        log.trace 'SP2600ZB >> uninstalled()'
    }
    unschedule()
}

void configure() {
    if (settings.trace) {
        log.trace 'SP2600ZB >> configure()'
    }
    try {
        unschedule()
    } catch (ignored) { }

    if (device.currentValue('energy') == null) {
        sendEvent(name: 'energy', value: 0, unit: 'kWh')
    }

    List cmds = []
    cmds += zigbee.configureReporting(0x0006, 0x0000, DataType.BOOLEAN, 0, 600, null) // State
    Integer powerChange = settings.powerChange == null ? getDefaultPowerChange() : settings.powerChange
    cmds += zigbee.configureReporting(0x0B04, 0x050B, DataType.INT16, 0, 600, powerChange) // Power
    // Logic made by device to trigger an event:
    // if energyChange <= energyValue; then trigger event
    // Since energyValue will only increase with time, we can only use minReportTime (300)
    cmds += zigbee.configureReporting(0x0702, 0x0000, DataType.UINT48, 300, 1800, 0) // Energy
    sendCommands(cmds)
}

Map parse(String description) {
    if (!description?.startsWith('read attr -')) {
        if (!description?.startsWith('catchall:')) {
            log.warn "SP2600ZB >> parse(description) ==> Unhandled event: ${description}"
        }
        return [:]
    }

    Map event = [:]
    Map descMap = zigbee.parseDescriptionAsMap(description)
    if (descMap.cluster == '0006' && descMap.attrId == '0000') {
        event.name = 'switch'
        event.value = getSwitchMap()[descMap.value]
    } else if (descMap.cluster == '0B04' && descMap.attrId == '050B') {
        event.name = 'power'
        event.value = getPower(descMap.value)
        event.unit = 'W'
    } else if (descMap.cluster == '0702' && descMap.attrId == '0000') {
        BigInteger newEnergyValue = getEnergy(descMap.value)
        if (newEnergyValue == 0) {
            log.info 'SP2600ZB >> Ignoring energy event (Caused: power outage or new pairing device)'
        } else {
            event.name = 'energy'
            event.value = newEnergyValue / 1000
            event.unit = 'kWh'
        }
    } else {
        log.warn "SP2600ZB >> parse(descMap) ==> Unhandled attribute: ${descMap}"
    }

    if (event.name != null && event.value != null) {
        event.descriptionText = "${device.getLabel()} ${event.name} is ${event.value}"
        if (event.unit) {
            event.descriptionText = "${event.descriptionText}${event.unit}"
        }
    }

    if (settings.trace) {
        log.trace "SP2600ZB >> parse(description) ==> ${event.name}: ${event.value}"
    }
    return event
}

void refresh() {
    if (settings.trace) {
        log.trace 'SP2600ZB >> refresh()'
    }
    if (state.updatedLastRanAt && now() < state.updatedLastRanAt + 2000) {
        if (settings.trace) {
            log.trace 'SP2600ZB >> refresh() ==> Ran within last 2 seconds so aborting'
        }
        return
    }
    state.updatedLastRanAt = now()

    List cmds = []
    cmds += zigbee.readAttribute(0x0006, 0x0000) // State
    cmds += zigbee.readAttribute(0x0B04, 0x050B) // Active power
    cmds += zigbee.readAttribute(0x0702, 0x0000) // Energy delivered
    sendCommands(cmds)
}

void off() {
    if (settings.trace) {
        log.trace 'SP2600ZB >> off()'
    }
    state.flashing = false
    List cmds = zigbee.command(0x0006, 0x00) // Off
    sendCommands(cmds)
}

void on() {
    if (settings.trace) {
        log.trace 'SP2600ZB >> on()'
    }
    state.flashing = false
    List cmds = zigbee.command(0x0006, 0x01) // On
    sendCommands(cmds)
}

void flash() {
    if (settings.trace) {
        log.trace 'SP2600ZB >> flash()'
    }
    state.flashing = true
    Long pulse = 2000 / 2
    flashOn(pulse)
}

void flash(Integer rateToFlash) {
    if (settings.trace) {
        log.trace "SP2600ZB >> flash(${rateToFlash})"
    }
    state.flashing = true
    Long pulse = rateToFlash / 2
    flashOn(pulse)
}

// Cannot be private to be executed by runIn
void flashOff(Long pulse) {
    if (settings.trace) {
        log.trace "SP2600ZB >> flashOff(${pulse})"
    }
    if (!state.flashing) {
        return
    }

    List cmds = zigbee.command(0x0006, 0x00) // Off
    sendCommands(cmds)
    runInMillis(pulse, 'flashOn', [data: pulse])
}

// Cannot be private to be executed by runIn
void flashOn(Long pulse) {
    if (settings.trace) {
        log.trace "SP2600ZB >> flashOn(${pulse})"
    }
    if (!state.flashing) {
        return
    }
    List cmds = zigbee.command(0x0006, 0x01) // On
    sendCommands(cmds)
    runInMillis(pulse, 'flashOff', [data: pulse])
}

private void sendCommands(List<Map> commands) {
    HubMultiAction actions = new HubMultiAction(commands, hubitat.device.Protocol.ZIGBEE)
    sendHubCommand(actions)
}

private Map getSwitchMap() {
    return [
        '00': 'off',
        '01': 'on',
    ]
}

private Double getPower(String value) {
    if (value == null) {
        return
    }
    return Integer.parseInt(value, 16) / 10
}

private BigInteger getEnergy(String value) {
    if (value == null) {
        return 0
    }
    return new BigInteger(value, 16)
}

private Integer getDefaultPowerChange() {
    return 5
}
