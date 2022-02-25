// Copyright 2022 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.firestore.core;

import static com.google.common.truth.Truth.assertThat;
import static com.google.firebase.firestore.testutil.TestUtil.andFilters;
import static com.google.firebase.firestore.testutil.TestUtil.filter;
import static com.google.firebase.firestore.testutil.TestUtil.orFilters;
import static com.google.firebase.firestore.util.LogicUtils.DnfTransform;
import static com.google.firebase.firestore.util.LogicUtils.applyAssociation;
import static com.google.firebase.firestore.util.LogicUtils.applyDistribution;
import static com.google.firebase.firestore.util.LogicUtils.computeDnf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class FilterTest {

  /** Helper method to get unique filters */
  FieldFilter nameFilter(String name) {
    return filter("name", "==", name);
  }

  private final FieldFilter A = nameFilter("A");
  private final FieldFilter B = nameFilter("B");
  private final FieldFilter C = nameFilter("C");
  private final FieldFilter D = nameFilter("D");
  private final FieldFilter E = nameFilter("E");
  private final FieldFilter F = nameFilter("F");
  private final FieldFilter G = nameFilter("G");
  private final FieldFilter H = nameFilter("H");
  private final FieldFilter I = nameFilter("I");

  @Test
  public void testFieldFilterMembers() {
    FieldFilter f = filter("foo", "==", "bar");
    assertEquals("foo", f.getField().toString());
    assertEquals("bar", f.getValue().getStringValue());
    assertEquals(FieldFilter.Operator.EQUAL, f.getOperator());
  }

  @Test
  public void testCompositeFilterMembers() {
    CompositeFilter andFilter = andFilters(A, B, C);
    assertTrue(andFilter.isConjunction());
    assertEquals(andFilter.getFilters(), Arrays.asList(A, B, C));
    CompositeFilter orFilter = orFilters(A, B, C);
    assertTrue(orFilter.isDisjunction());
    assertEquals(andFilter.getFilters(), Arrays.asList(A, B, C));
  }

  @Test
  public void testCompositeFilterNestedChecks() {
    CompositeFilter andFilter1 = andFilters(A, B, C);
    assertTrue(andFilter1.isFlat());
    assertTrue(andFilter1.isConjunction());
    assertFalse(andFilter1.isDisjunction());
    assertTrue(andFilter1.isFlatConjunction());

    CompositeFilter orFilter1 = orFilters(A, B, C);
    assertFalse(orFilter1.isConjunction());
    assertTrue(orFilter1.isDisjunction());
    assertTrue(orFilter1.isFlat());
    assertFalse(orFilter1.isFlatConjunction());

    CompositeFilter andFilter2 = andFilters(D, andFilter1);
    assertTrue(andFilter2.isConjunction());
    assertFalse(andFilter2.isDisjunction());
    assertFalse(andFilter2.isFlat());
    assertFalse(andFilter2.isFlatConjunction());

    CompositeFilter orFilter2 = orFilters(D, andFilter1);
    assertFalse(orFilter2.isConjunction());
    assertTrue(orFilter2.isDisjunction());
    assertFalse(orFilter2.isFlat());
    assertFalse(orFilter2.isFlatConjunction());
  }

  @Test
  public void testFieldFilterAssociativity() {
    FieldFilter f = filter("foo", "==", "bar");
    assertEquals(f, applyAssociation(f));
  }

  @Test
  public void testCompositeFilterAssociativity() {
    // AND(AND(X)) --> X
    CompositeFilter compositeFilter1 = andFilters(andFilters(A));
    assertEquals(A, applyAssociation(compositeFilter1));

    // OR(OR(X)) --> X
    CompositeFilter compositeFilter2 = orFilters(orFilters(A));
    assertEquals(A, applyAssociation(compositeFilter2));

    // (A | (B) | ((C) | (D | E)) | (F | (G & (H & I))) --> A | B | C | D | E | F | (G & H & I)
    CompositeFilter complexFilter =
        orFilters(
            A,
            andFilters(B),
            orFilters(orFilters(C), orFilters(D, E)),
            orFilters(F, andFilters(G, andFilters(H, I))));
    CompositeFilter expectedResult = orFilters(A, B, C, D, E, F, andFilters(G, H, I));
    assertThat(applyAssociation(complexFilter)).isEqualTo(expectedResult);
  }

  @Test
  public void testFieldFilterDistributionOverFieldFilter() {
    assertThat(applyDistribution(A, B)).isEqualTo(andFilters(A, B));
    assertThat(applyDistribution(B, A)).isEqualTo(andFilters(B, A));
  }

  @Test
  public void testFieldFilterDistributionOverAndFilter() {
    // (A & B & C) & D = (A & B & C & D)
    assertThat(applyDistribution(andFilters(A, B, C), D)).isEqualTo(andFilters(A, B, C, D));
  }

  @Test
  public void testFieldFilterDistributionOverOrFilter() {
    // A & (B | C | D) = (A & B) | (A & C) | (A & D)
    // (B | C | D) & A = (A & B) | (A & C) | (A & D)
    CompositeFilter expected = orFilters(andFilters(A, B), andFilters(A, C), andFilters(A, D));
    assertThat(applyDistribution(A, orFilters(B, C, D))).isEqualTo(expected);
    assertThat(applyDistribution(orFilters(B, C, D), A)).isEqualTo(expected);
  }

  // The following four tests cover:
  // AND distribution for AND filter and AND filter.
  // AND distribution for OR filter and AND filter.
  // AND distribution for AND filter and OR filter.
  // AND distribution for OR filter and OR filter.
  @Test
  public void testAndFilterDistributionWithAndFilter() {
    // (A & B) & (C & D) --> (A & B & C & D)
    CompositeFilter expectedResult = andFilters(A, B, C, D);
    assertThat(applyDistribution(andFilters(A, B), andFilters(C, D))).isEqualTo(expectedResult);
  }

  @Test
  public void testAndFilterDistributionWithOrFilter() {
    // (A & B) & (C | D) --> (A & B & C) | (A & B & D)
    CompositeFilter expectedResult = orFilters(andFilters(A, B, C), andFilters(A, B, D));
    assertThat(applyDistribution(andFilters(A, B), orFilters(C, D))).isEqualTo(expectedResult);
  }

  @Test
  public void testOrFilterDistributionWithAndFilter() {
    // (A | B) & (C & D) --> (C & D & A) | (C & D & B)
    CompositeFilter expectedResult = orFilters(andFilters(C, D, A), andFilters(C, D, B));
    assertThat(applyDistribution(orFilters(A, B), andFilters(C, D))).isEqualTo(expectedResult);
  }

  @Test
  public void testOrFilterDistributionWithOrFilter() {
    // (A | B) & (C | D) --> (A & C) | (A & D) | (B & C) | (B & D)
    CompositeFilter expectedResult =
        orFilters(andFilters(A, C), andFilters(A, D), andFilters(B, C), andFilters(B, D));
    assertThat(applyDistribution(orFilters(A, B), orFilters(C, D))).isEqualTo(expectedResult);
  }

  @Test
  public void testFieldFilterComputeDnf() {
    assertThat(computeDnf(A)).isEqualTo(A);
    assertThat(DnfTransform(andFilters(A))).isEqualTo(Collections.singletonList(A));
    assertThat(DnfTransform(orFilters(A))).isEqualTo(Collections.singletonList(A));
  }

  @Test
  public void testComputeDnfFlatAndFilter() {
    CompositeFilter compositeFilter = andFilters(A, B, C);
    assertThat(computeDnf(compositeFilter)).isEqualTo(compositeFilter);
    assertThat(DnfTransform(compositeFilter)).isEqualTo(Collections.singletonList(compositeFilter));
  }

  @Test
  public void testComputeDnfFlatOrFilter() {
    CompositeFilter compositeFilter = orFilters(A, B, C);
    assertThat(computeDnf(compositeFilter)).isEqualTo(compositeFilter);
    Filter[] expectedDnfTerms = {A, B, C};
    assertThat(DnfTransform(compositeFilter)).isEqualTo(Arrays.asList(expectedDnfTerms));
  }

  @Test
  public void testComputeDnf1() {
    // A & (B | C) --> (A & B) | (A & C)
    CompositeFilter compositeFilter = andFilters(A, orFilters(B, C));
    Filter[] expectedDnfTerms = {andFilters(A, B), andFilters(A, C)};
    CompositeFilter expectedResult = orFilters(expectedDnfTerms);
    assertThat(computeDnf(compositeFilter)).isEqualTo(expectedResult);
    assertThat(DnfTransform(compositeFilter)).isEqualTo(Arrays.asList(expectedDnfTerms));
  }

  @Test
  public void testComputeDnf2() {
    // ((A)) & (B & C) --> A & B & C
    CompositeFilter compositeFilter = andFilters(andFilters(andFilters(A)), andFilters(B, C));
    CompositeFilter expectedResult = andFilters(A, B, C);
    assertThat(computeDnf(compositeFilter)).isEqualTo(expectedResult);
    assertThat(DnfTransform(compositeFilter)).isEqualTo(Collections.singletonList(expectedResult));
  }

  @Test
  public void testComputeDnf3() {
    // A | (B & C)
    CompositeFilter compositeFilter = orFilters(A, andFilters(B, C));
    assertThat(computeDnf(compositeFilter)).isEqualTo(compositeFilter);
    Filter[] expectedDnfTerms = {A, andFilters(B, C)};
    assertThat(DnfTransform(compositeFilter)).isEqualTo(Arrays.asList(expectedDnfTerms));
  }

  @Test
  public void testComputeDnf4() {
    // A | (B & C) | ( ((D)) | (E | F) | (G & H) ) --> A | (B & C) | D | E | F | (G & H)
    CompositeFilter compositeFilter =
        orFilters(
            A,
            andFilters(B, C),
            orFilters(andFilters(orFilters(D)), orFilters(E, F), andFilters(G, H)));
    Filter[] expectedDnfTerms = {A, andFilters(B, C), D, E, F, andFilters(G, H)};
    CompositeFilter expectedResult = orFilters(expectedDnfTerms);
    assertThat(computeDnf(compositeFilter)).isEqualTo(expectedResult);
    assertThat(DnfTransform(compositeFilter)).isEqualTo(Arrays.asList(expectedDnfTerms));
  }

  @Test
  public void testComputeDnf5() {
    //    A & (B | C) & ( ((D)) & (E | F) & (G & H) )
    // -> A & (B | C) & D & (E | F) & G & H
    // -> ((A & B) | (A & C)) & D & (E | F) & G & H
    // -> ((A & B & D) | (A & C & D)) & (E|F) & G & H
    // -> ((A & B & D & E) | (A & B & D & F) | (A & C & D & E) | (A & C & D & F)) & G & H
    // -> ((A&B&D&E&G) | (A & B & D & F & G) | (A & C & D & E & G) | (A & C & D & F & G)) & H
    // -> (A&B&D&E&G&H) | (A&B&D&F&G&H) | (A & C & D & E & G & H) | (A & C & D & F & G & H)
    CompositeFilter compositeFilter =
        andFilters(
            A,
            orFilters(B, C),
            andFilters(andFilters(orFilters(D)), orFilters(E, F), andFilters(G, H)));
    Filter[] expectedDnfTerms = {
      andFilters(D, E, G, H, A, B),
      andFilters(D, F, G, H, A, B),
      andFilters(D, E, G, H, A, C),
      andFilters(D, F, G, H, A, C)
    };
    CompositeFilter expectedResult = orFilters(expectedDnfTerms);
    assertThat(computeDnf(compositeFilter)).isEqualTo(expectedResult);
    assertThat(DnfTransform(compositeFilter)).isEqualTo(Arrays.asList(expectedDnfTerms));
  }

  @Test
  public void testComputeDnf6() {
    // A & (B | (C & (D | (E & F))))
    // -> A & (B | (C & D) | (C & E & F))
    // -> (A & B) | (A & C & D) | (A & C & E & F)
    CompositeFilter compositeFilter =
        andFilters(A, orFilters(B, andFilters(C, orFilters(D, andFilters(E, F)))));
    Filter[] expectedDnfTerms = {andFilters(A, B), andFilters(C, D, A), andFilters(E, F, C, A)};
    CompositeFilter expectedResult = orFilters(expectedDnfTerms);
    assertThat(computeDnf(compositeFilter)).isEqualTo(expectedResult);
    assertThat(DnfTransform(compositeFilter)).isEqualTo(Arrays.asList(expectedDnfTerms));
  }

  @Test
  public void testComputeDnf7() {
    // ( (A|B) & (C|D) ) | ( (E|F) & (G|H) )
    // -> (A&C)|(A&D)|(B&C)(B&D)|(E&G)|(E&H)|(F&G)|(F&H)
    CompositeFilter compositeFilter =
        orFilters(
            andFilters(orFilters(A, B), orFilters(C, D)),
            andFilters(orFilters(E, F), orFilters(G, H)));
    Filter[] expectedDnfTerms = {
      andFilters(A, C),
      andFilters(A, D),
      andFilters(B, C),
      andFilters(B, D),
      andFilters(E, G),
      andFilters(E, H),
      andFilters(F, G),
      andFilters(F, H)
    };
    CompositeFilter expectedResult = orFilters(expectedDnfTerms);
    assertThat(computeDnf(compositeFilter)).isEqualTo(expectedResult);
    assertThat(DnfTransform(compositeFilter)).isEqualTo(Arrays.asList(expectedDnfTerms));
  }

  @Test
  public void testComputeDnf8() {
    // ( (A&B) | (C&D) ) & ( (E&F) | (G&H) )
    // -> A&B&E&F | A&B&G&H | C&D&E&F | C&D&G&H
    CompositeFilter compositeFilter =
        andFilters(
            orFilters(andFilters(A, B), andFilters(C, D)),
            orFilters(andFilters(E, F), andFilters(G, H)));
    Filter[] expectedDnfTerms = {
      andFilters(E, F, A, B), andFilters(G, H, A, B), andFilters(E, F, C, D), andFilters(G, H, C, D)
    };
    CompositeFilter expectedResult = orFilters(expectedDnfTerms);
    assertThat(computeDnf(compositeFilter)).isEqualTo(expectedResult);
    assertThat(DnfTransform(compositeFilter)).isEqualTo(Arrays.asList(expectedDnfTerms));
  }
}
