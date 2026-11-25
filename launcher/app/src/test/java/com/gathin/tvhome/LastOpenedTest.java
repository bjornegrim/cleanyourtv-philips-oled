package com.gathin.tvhome;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LastOpenedTest {
    @Test public void swedishPhrasesStartLowerCaseAfterSenastOppnad() {
        assertEquals("för 1 minut sedan", LastOpened.midSentence("För 1 minut sedan", "sv"));
        assertEquals("i går", LastOpened.midSentence("I går", "sv"));
        assertEquals("12 okt.", LastOpened.midSentence("12 okt.", "sv"));
    }

    @Test public void englishKeepsMonthNamesCapitalised() {
        assertEquals("yesterday", LastOpened.midSentence("Yesterday", "en"));
        assertEquals("today", LastOpened.midSentence("Today", "en"));
        assertEquals("Oct 12", LastOpened.midSentence("Oct 12", "en"));
        assertEquals("5 minutes ago", LastOpened.midSentence("5 minutes ago", "en"));
    }

    @Test public void otherLanguagesAreLeftAlone() {
        assertEquals("Gestern", LastOpened.midSentence("Gestern", "de"));
        assertEquals("", LastOpened.midSentence("", "sv"));
    }
}
