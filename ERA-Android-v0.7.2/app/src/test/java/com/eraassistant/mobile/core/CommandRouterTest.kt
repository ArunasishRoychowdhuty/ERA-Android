package com.eraassistant.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandRouterTest {
    private val router = CommandRouter()

    @Test fun torchCommandsAreInstant() {
        assertEquals(AssistantCommand.SetTorch(true), router.route("টর্চ অন কর"))
        assertEquals(AssistantCommand.SetTorch(false), router.route("টর্চ বন্ধ কর"))
    }

    @Test fun appLaunchCommandsAreRecognized() {
        assertEquals(AssistantCommand.LaunchApp(KnownApp.YOUTUBE), router.route("YouTube খোলো"))
        assertEquals(AssistantCommand.LaunchApp(KnownApp.GMAIL), router.route("Gmail খোলো"))
        assertEquals(AssistantCommand.OpenCamera, router.route("Camera খোলো"))
        assertEquals(AssistantCommand.LaunchAppByName("Telegram"), router.route("Telegram খোলো"))
    }

    @Test fun volumePercentIsBounded() {
        assertEquals(AssistantCommand.SetMediaVolumePercent(50), router.route("ভলিউম ৫০ শতাংশ"))
        assertEquals(AssistantCommand.SetMediaVolumePercent(100), router.route("volume 140 percent"))
    }

    @Test fun deviceInformationCommandsAreRecognized() {
        assertEquals(AssistantCommand.ReadBattery, router.route("ব্যাটারি কত"))
        assertEquals(AssistantCommand.ReadDateTime, router.route("এখন কয়টা"))
    }

    @Test fun dialerAndMuteCommandsAreBounded() {
        assertEquals(AssistantCommand.DialNumber("9876543210"), router.route("কল কর 9876543210"))
        assertEquals(AssistantCommand.PickContactForDial, router.route("মাকে কল কর"))
        assertEquals(AssistantCommand.SetMediaVolumePercent(0), router.route("mute"))
        assertEquals(AssistantCommand.SetMediaVolumePercent(100), router.route("unmute"))
    }

    @Test fun whatsappMessageRemainsDraftCommand() {
        assertEquals(AssistantCommand.ShareTextToWhatsApp("Hi"), router.route("WhatsApp-এ Hi লিখে দাও"))
        assertTrue(router.route("WhatsApp খুলে সাহেবকে Hi লিখে দাও") is AssistantCommand.UnsupportedComplexTask)
        assertEquals(AssistantCommand.ShareTextToWhatsApp("Hello World"), router.route("WhatsApp-এ Hello World লিখে দাও"))
    }

    @Test fun smsMessageCreatesDraft() {
        assertEquals(AssistantCommand.CreateSmsDraft("Hello"), router.route("SMS-এ Hello লিখে দাও"))
    }

    @Test fun webAndMapQueriesAreRouted() {
        assertEquals(AssistantCommand.OpenWebSearch("weather today"), router.route("search for weather today"))
        assertEquals(AssistantCommand.OpenMapSearch("Ranaghat station"), router.route("map search Ranaghat station"))
    }

    @Test fun channelVolumeCommandsAreSupported() {
        assertEquals(AssistantCommand.AdjustVolume(VolumeStream.RINGTONE, VolumeDirection.UP), router.route("রিংটোন ভলিউম বাড়াও"))
        assertEquals(AssistantCommand.SetVolumePercent(VolumeStream.ALARM, 30), router.route("অ্যালার্ম ভলিউম ৩০%"))
        assertEquals(AssistantCommand.AdjustVolume(VolumeStream.NOTIFICATION, VolumeDirection.DOWN), router.route("notification volume down"))
    }

    @Test fun compoundCommandsBecomeBoundedSequences() {
        val result = router.route("টর্চ অন করে YouTube খোলো")
        assertTrue(result is AssistantCommand.RunSequence)
        result as AssistantCommand.RunSequence
        assertEquals(2, result.commands.size)
        assertEquals(AssistantCommand.SetTorch(true), result.commands[0])
        assertEquals(AssistantCommand.LaunchApp(KnownApp.YOUTUBE), result.commands[1])
    }

    @Test fun stopCommandIsRecognized() {
        assertEquals(AssistantCommand.CancelCurrentTask, router.route("ERA stop"))
    }

    @Test fun complexTasksAreNotReducedToAppLaunch() {
        assertTrue(router.route("Instagram খুলে একটা পোস্ট কর") is AssistantCommand.UnsupportedComplexTask)
        assertTrue(router.route("WhatsApp-এ সাহেবকে Hi লিখে দাও") is AssistantCommand.UnsupportedComplexTask)
    }
}
