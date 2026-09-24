package com.btrace.viewer.data

import com.btrace.viewer.model.BinderDev
import com.btrace.viewer.model.BinderEvent
import com.btrace.viewer.parser.DecodedArgument
import com.btrace.viewer.parser.InterfaceIndex
import com.btrace.viewer.parser.MethodResolver
import com.btrace.viewer.parser.MethodSignature
import com.btrace.viewer.parser.ParcelArgumentDecoder
import com.btrace.viewer.parser.ParcelDecodeResult
import com.btrace.viewer.parser.ParcelParser
import com.btrace.viewer.parser.ServiceManagerCatalog
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.StringWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EventRepositoryExportTest {
    @Test
    fun `export keeps the decoded request and reply together after storage`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val descriptor = "example.ITest"
        val resolver: MethodResolver = mock()
        whenever(resolver.getMethodSignature(eq(descriptor), eq(7), eq(listOf("example.client")), any()))
            .thenReturn(MethodSignature("getValue", listOf("int"), "int"))
        val apps: AppRepository = mock()
        whenever(apps.getPackageNameForUid(10086)).thenReturn("example.client")
        val arguments: ParcelArgumentDecoder = mock()
        // ShadowParcel does not accept the native wire format; argument decoding has separate tests.
        whenever(arguments.decode(any(), eq(listOf("int")))).thenReturn(ParcelDecodeResult(
            listOf(DecodedArgument(0, "int", "7", DecodedArgument.Status.SUCCESS)), 4, 0, true,
        ))
        val repo = EventRepository(
            parcelParser = ParcelParser(methodResolver = resolver),
            methodResolver = resolver,
            argumentDecoder = arguments,
            appRepository = apps,
            interfaceIndex = InterfaceIndex(context),
            serviceManagerCatalog = ServiceManagerCatalog(),
            coldStore = ColdEventStore(context),
        )
        repo.setTargetUid(10086)

        val tokenSize = ((descriptor.length + 1) * 2 + 3) and 0x3.inv()
        val parcel = ByteBuffer.allocate(16 + tokenSize + 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0).putInt(0).putInt(0x53595354).putInt(descriptor.length)
            for (c in descriptor) putShort(c.code.toShort())
            putShort(0)
            position(16 + tokenSize)
            putInt(7)
        }.array()
        val request = BinderEvent(
            0, 1_000_000_000, 1, 10086, 7, 0, parcel,
            binderDev = BinderDev.BINDER, pairId = 42,
        )
        val response = BinderEvent(
            1, 1_002_500_000, 2, 1000, 0, 0,
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(0).putInt(42).array(),
            isReply = true, binderDev = BinderDev.BINDER, toUid = 10086, pairId = 42,
        )
        repo.addEvent(request)
        repo.addEvent(response)
        assertEquals(descriptor, response.interfaceName)
        assertEquals("42", response.parsedReply?.value)

        repo.setFilter(EventFilter(methodContains = "notInThisTrace"))
        val output = StringWriter()
        assertEquals(2L, repo.exportJson(output))
        val events = JSONObject(output.toString()).getJSONArray("events")
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            val req = event.getJSONObject("request")
            val reply = event.getJSONObject("response")
            assertEquals("0", req.getString("eventId"))
            assertEquals("getValue", req.getString("methodName"))
            assertEquals("7", req.getJSONArray("parsedArgs").getJSONObject(0).getString("displayValue"))
            assertEquals("1", reply.getString("eventId"))
            assertEquals("42", reply.getJSONObject("parsedReply").getString("value"))
            assertEquals(2.5, reply.getDouble("latencyMs"), 0.0)
            assertEquals("received", event.getString("responseStatus"))
        }
    }
}
