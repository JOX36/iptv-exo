package com.jox3.tvdesktop

import com.jox3.tvdesktop.data.XtreamApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ParseLoginTest {
    @Test fun hostSimpleAgregaHttp() {
        val a = XtreamApi.parseLogin("servidor.com:8080", "ana", "123")!!
        assertEquals("http://servidor.com:8080", a.host)
        assertEquals("ana", a.user); assertEquals("123", a.pass)
    }

    @Test fun quitaBarraFinalYEspacios() {
        val a = XtreamApi.parseLogin("  https://tv.net/  ", " ana ", " 123 ")!!
        assertEquals("https://tv.net", a.host); assertEquals("ana", a.user); assertEquals("123", a.pass)
    }

    @Test fun enlaceGetPhp() {
        val a = XtreamApi.parseLogin("http://tv.net:80/get.php?username=ana&password=c%40ve&type=m3u_plus", "", "")!!
        assertEquals("http://tv.net:80", a.host); assertEquals("ana", a.user); assertEquals("c@ve", a.pass)
    }

    @Test fun enlaceDeCanal() {
        val a = XtreamApi.parseLogin("http://tv.net:8080/live/ana/clave/123.ts", "", "")!!
        assertEquals("http://tv.net:8080", a.host); assertEquals("ana", a.user); assertEquals("clave", a.pass)
    }

    @Test fun faltanDatos() {
        assertNull(XtreamApi.parseLogin("", "ana", "1"))
        assertNull(XtreamApi.parseLogin("tv.net", "", "1"))
        assertNull(XtreamApi.parseLogin("tv.net", "ana", ""))
    }

    @Test fun laContrasenaNoSaleEnTexto() {
        val a = XtreamApi.parseLogin("tv.net", "ana", "secreta")!!
        assert("secreta" !in a.toString())
    }
}
