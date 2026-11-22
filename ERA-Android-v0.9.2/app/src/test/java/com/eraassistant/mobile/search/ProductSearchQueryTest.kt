package com.eraassistant.mobile.search

import org.junit.Assert.*
import org.junit.Test

class ProductSearchQueryTest {
    @Test fun rejectsBlankSearch() { assertNull(ProductSearchQuery.shoppingUrl("  ")) }
    @Test fun encodesShoppingQuery() {
        assertEquals("https://www.google.com/search?tbm=shop&q=wireless%20mouse", ProductSearchQuery.shoppingUrl(" wireless   mouse "))
    }
    @Test fun boundsQueryLength() { assertTrue(ProductSearchQuery.normalize("x".repeat(500)).length <= 180) }
}
