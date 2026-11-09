package cc.ptoe.messenger.data.cloud

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DevServerDiscoveryTest {
    @Test
    fun acceptsOnlyCompatibleServerIdentityAndValidPorts() {
        assertEquals(3456, discoveryPort("""{"service":"messenger-cloud","version":1,"port":3456}"""))
        for (payload in listOf(
            "not JSON", "[]", "{}",
            """{"service":"other","version":1,"port":3000}""",
            """{"service":"messenger-cloud","version":2,"port":3000}""",
            """{"service":"messenger-cloud","version":1,"port":0}""",
            """{"service":"messenger-cloud","version":1,"port":65536}""",
            """{"service":"messenger-cloud","version":1,"port":{}}"""
        )) assertNull(discoveryPort(payload), payload)
    }
}
