/*
 * Copyright 2014 LinkedIn Corp. All rights reserved.
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.linkedin.photon.ml.diagnostics.hl

import com.linkedin.photon.ml.diagnostics.reporting._
import com.xeiam.xchart.{StyleManager, ChartBuilder, QuickChart, Chart}

/**
 * Simple, naive transformation (don't attempt to do anything clever with plots, for example) for HL test results
 */
class NaiveHosmerLemeshowToPhysicalReportTransformer extends LogicalToPhysicalReportTransformer[HosmerLemeshowReport, SectionPhysicalReport] {

  import NaiveHosmerLemeshowToPhysicalReportTransformer._

  def transform(hlr: HosmerLemeshowReport): SectionPhysicalReport = {
    val plot = generatePlot(hlr)
    val explanatoryText = generateExplanatoryText(hlr)
    new SectionPhysicalReport(Seq(plot, explanatoryText), SECTION_HEADER)
  }

  private def generatePlot(hlr: HosmerLemeshowReport): SectionPhysicalReport = {
    val xSeries = hlr.histogram.map(b => 100.0 * (b.lowerBound + b.upperBound) / 2.0)
    val ySeries1 = hlr.histogram.map(b => {
      val totalCount = b.observedNegCount + b.observedPosCount
      val percent = if (totalCount > 0) b.observedPosCount.toDouble / totalCount else 0.0
      100.0 * percent
    })
    val ySeries2 = xSeries
    val builder = new ChartBuilder()
    builder
      .chartType(StyleManager.ChartType.Bar)
      .theme(StyleManager.ChartTheme.XChart)
      .title("Observed positive rate versus predicted positive rate")
      .xAxisTitle("Predicted positive rate")
      .yAxisTitle("Observed positive rate")
    val chart = builder.build()
    chart.addSeries("Observed", xSeries, ySeries1)
    chart.addSeries("Expected", xSeries, ySeries2)
    new SectionPhysicalReport(Seq(new PlotPhysicalReport(chart)), PLOT_HEADER)
  }

  private def generateExplanatoryText(hlr: HosmerLemeshowReport): SectionPhysicalReport = {
    val binningMsg = new SectionPhysicalReport(Seq(new SimpleTextPhysicalReport(hlr.binningMsg)), BINNING_HEADER)
    val chisquareCalcMsg = new SectionPhysicalReport(Seq(new SimpleTextPhysicalReport(hlr.chiSquareCalculationMsg)), CHI_SQUARE_HEADER)
    val cutoffMsg = new BulletedListPhysicalReport(hlr.getCutoffAnalysis.map(x => new SimpleTextPhysicalReport(x)))
    val analysisMsg = new BulletedListPhysicalReport(Seq(
      new SimpleTextPhysicalReport(hlr.getTestDescription()),
      new SimpleTextPhysicalReport(hlr.getPointProbabilityAnalysis()),
      cutoffMsg))
    new SectionPhysicalReport(Seq(analysisMsg, binningMsg, chisquareCalcMsg), ANALYSIS_HEADER)
  }
}

object NaiveHosmerLemeshowToPhysicalReportTransformer {
  val SECTION_HEADER = "Hosmer-Lemeshow Goodness-of-Fit Test for Logistic Regression"
  val ANALYSIS_HEADER = "Analysis"
  val PLOT_HEADER = "Plots"
  val BINNING_HEADER = "Messages generated during histogram calculation"
  val CHI_SQUARE_HEADER = "Messages generated during Chi square calculation"
}