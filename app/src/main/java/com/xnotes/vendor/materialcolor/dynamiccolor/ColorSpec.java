/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xnotes.vendor.materialcolor.dynamiccolor;

import com.xnotes.vendor.materialcolor.dynamiccolor.DynamicScheme.Platform;
import com.xnotes.vendor.materialcolor.hct.Hct;
import com.xnotes.vendor.materialcolor.palettes.TonalPalette;
import java.util.Optional;

/** An interface defining all the necessary methods that could be different between specs. */
public interface ColorSpec {

  /** All available spec versions. */
  public enum SpecVersion {
    SPEC_2021,
    SPEC_2025,
    SPEC_2026,
  }

  ////////////////////////////////////////////////////////////////
  // Main Palettes                                              //
  ////////////////////////////////////////////////////////////////

  
  public DynamicColor primaryPaletteKeyColor();

  
  public DynamicColor secondaryPaletteKeyColor();

  
  public DynamicColor tertiaryPaletteKeyColor();

  
  public DynamicColor neutralPaletteKeyColor();

  
  public DynamicColor neutralVariantPaletteKeyColor();

  
  public DynamicColor errorPaletteKeyColor();

  ////////////////////////////////////////////////////////////////
  // Surfaces [S]                                               //
  ////////////////////////////////////////////////////////////////

  
  public DynamicColor background();

  
  public DynamicColor onBackground();

  
  public DynamicColor surface();

  
  public DynamicColor surfaceDim();

  
  public DynamicColor surfaceBright();

  
  public DynamicColor surfaceContainerLowest();

  
  public DynamicColor surfaceContainerLow();

  
  public DynamicColor surfaceContainer();

  
  public DynamicColor surfaceContainerHigh();

  
  public DynamicColor surfaceContainerHighest();

  
  public DynamicColor onSurface();

  
  public DynamicColor surfaceVariant();

  
  public DynamicColor onSurfaceVariant();

  
  public DynamicColor inverseSurface();

  
  public DynamicColor inverseOnSurface();

  
  public DynamicColor outline();

  
  public DynamicColor outlineVariant();

  
  public DynamicColor shadow();

  
  public DynamicColor scrim();

  
  public DynamicColor surfaceTint();

  ////////////////////////////////////////////////////////////////
  // Primaries [P]                                              //
  ////////////////////////////////////////////////////////////////

  
  public DynamicColor primary();

  
  public DynamicColor primaryDim();

  
  public DynamicColor onPrimary();

  
  public DynamicColor primaryContainer();

  
  public DynamicColor onPrimaryContainer();

  
  public DynamicColor inversePrimary();

  ////////////////////////////////////////////////////////////////
  // Secondaries [Q]                                            //
  ////////////////////////////////////////////////////////////////

  
  public DynamicColor secondary();

  
  public DynamicColor secondaryDim();

  
  public DynamicColor onSecondary();

  
  public DynamicColor secondaryContainer();

  
  public DynamicColor onSecondaryContainer();

  ////////////////////////////////////////////////////////////////
  // Tertiaries [T]                                             //
  ////////////////////////////////////////////////////////////////

  
  public DynamicColor tertiary();

  
  public DynamicColor tertiaryDim();

  
  public DynamicColor onTertiary();

  
  public DynamicColor tertiaryContainer();

  
  public DynamicColor onTertiaryContainer();

  ////////////////////////////////////////////////////////////////
  // Errors [E]                                                 //
  ////////////////////////////////////////////////////////////////

  
  public DynamicColor error();

  
  public DynamicColor errorDim();

  
  public DynamicColor onError();

  
  public DynamicColor errorContainer();

  
  public DynamicColor onErrorContainer();

  ////////////////////////////////////////////////////////////////
  // Primary Fixed Colors [PF]                                  //
  ////////////////////////////////////////////////////////////////

  
  public DynamicColor primaryFixed();

  
  public DynamicColor primaryFixedDim();

  
  public DynamicColor onPrimaryFixed();

  
  public DynamicColor onPrimaryFixedVariant();

  ////////////////////////////////////////////////////////////////
  // Secondary Fixed Colors [QF]                                //
  ////////////////////////////////////////////////////////////////

  
  public DynamicColor secondaryFixed();

  
  public DynamicColor secondaryFixedDim();

  
  public DynamicColor onSecondaryFixed();

  
  public DynamicColor onSecondaryFixedVariant();

  ////////////////////////////////////////////////////////////////
  // Tertiary Fixed Colors [TF]                                 //
  ////////////////////////////////////////////////////////////////

  
  public DynamicColor tertiaryFixed();

  
  public DynamicColor tertiaryFixedDim();

  
  public DynamicColor onTertiaryFixed();

  
  public DynamicColor onTertiaryFixedVariant();

  //////////////////////////////////////////////////////////////////
  // Android-only Colors                                          //
  //////////////////////////////////////////////////////////////////

  
  public DynamicColor controlActivated();

  
  public DynamicColor controlNormal();

  
  public DynamicColor controlHighlight();

  
  public DynamicColor textPrimaryInverse();

  
  public DynamicColor textSecondaryAndTertiaryInverse();

  
  public DynamicColor textPrimaryInverseDisableOnly();

  
  public DynamicColor textSecondaryAndTertiaryInverseDisabled();

  
  public DynamicColor textHintInverse();

  ////////////////////////////////////////////////////////////////
  // Other                                                      //
  ////////////////////////////////////////////////////////////////

  
  public DynamicColor highestSurface(DynamicScheme s);

  /////////////////////////////////////////////////////////////////
  // Color value calculations                                    //
  /////////////////////////////////////////////////////////////////

  Hct getHct(DynamicScheme scheme, DynamicColor color);

  double getTone(DynamicScheme scheme, DynamicColor color);

  //////////////////////////////////////////////////////////////////
  // Scheme Palettes                                              //
  //////////////////////////////////////////////////////////////////

  
  public TonalPalette getPrimaryPalette(
      Variant variant, Hct sourceColorHct, boolean isDark, Platform platform, double contrastLevel);

  
  public TonalPalette getSecondaryPalette(
      Variant variant, Hct sourceColorHct, boolean isDark, Platform platform, double contrastLevel);

  
  public TonalPalette getTertiaryPalette(
      Variant variant, Hct sourceColorHct, boolean isDark, Platform platform, double contrastLevel);

  
  public TonalPalette getNeutralPalette(
      Variant variant, Hct sourceColorHct, boolean isDark, Platform platform, double contrastLevel);

  
  public TonalPalette getNeutralVariantPalette(
      Variant variant, Hct sourceColorHct, boolean isDark, Platform platform, double contrastLevel);

  
  public Optional<TonalPalette> getErrorPalette(
      Variant variant, Hct sourceColorHct, boolean isDark, Platform platform, double contrastLevel);
}
