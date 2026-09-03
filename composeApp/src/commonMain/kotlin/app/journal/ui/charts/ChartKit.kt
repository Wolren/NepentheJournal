package app.journal.ui.charts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.journal.ui.theme.ThemeManager
import com.patrykandpatrick.vico.compose.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisTickComponent
import com.patrykandpatrick.vico.compose.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.compose.cartesian.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.compose.common.DashedShape
import com.patrykandpatrick.vico.compose.common.Fill
import com.patrykandpatrick.vico.compose.cartesian.axis.Axis
import com.patrykandpatrick.vico.compose.common.component.LineComponent
import com.patrykandpatrick.vico.compose.common.component.ShapeComponent
import com.patrykandpatrick.vico.compose.common.component.TextComponent
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent

private val chartCardShape = RoundedCornerShape(10.dp)

/**
 * Single card wrapper every chart in the app goes through.
 * 10 dp / surfaceVariant / hairline outline - same as StatCard, ToleranceCard, SectionCard.
 */
@Composable
fun ChartCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = chartCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.08f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(14.dp).fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/** Shared palette - every Canvas / Vico chart reads from here so grid, axes, and labels match. */
object ChartTheme {
    @Composable
    fun gridColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.08f)
    @Composable
    fun gridColorFaint(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.05f)
    @Composable
    fun axisLineColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f)
    @Composable
    fun labelColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    @Composable
    fun labelColorFaint(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.40f)
    @Composable
    fun trackColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.10f)
    @Composable
    fun primary(): Color = MaterialTheme.colorScheme.primary
    @Composable
    fun isDark(): Boolean = ThemeManager.instance.isDarkTheme()
}

// ── Vico helpers ──────────────────────────────────────────────────────────────

/** Muted axis label style - 10sp, matches Duration section y-labels (alpha 0.4). */
@Composable
fun chartLabelComponent(color: Color = ChartTheme.labelColor()): TextComponent =
    rememberAxisLabelComponent(
        style = TextStyle(color = color, fontSize = 10.sp),
    )

@Composable
fun chartAxisLine(color: Color = ChartTheme.axisLineColor()): LineComponent =
    rememberAxisLineComponent(fill = Fill(color), thickness = 1.dp)

@Composable
fun chartAxisTick(color: Color = ChartTheme.axisLineColor()): LineComponent =
    rememberAxisTickComponent(fill = Fill(color), thickness = 1.dp)

@Composable
fun chartGuideline(color: Color = ChartTheme.gridColor()): LineComponent =
    rememberAxisGuidelineComponent(
        fill = Fill(color),
        thickness = 1.dp,
        shape = DashedShape(dashLength = 4.dp, gapLength = 4.dp),
    )

/** Forest primary line with soft fill + cubic smoothing - mirrors Duration intensity curve. */
@Composable
fun forestLine(
    color: Color = MaterialTheme.colorScheme.primary,
): LineCartesianLayer.Line = LineCartesianLayer.Line(
    fill = LineCartesianLayer.LineFill.single(Fill(color)),
    stroke = LineCartesianLayer.LineStroke.Continuous(thickness = 2.dp),
    areaFill = LineCartesianLayer.AreaFill.single(Fill(color.copy(alpha = 0.12f))),
    interpolator = LineCartesianLayer.Interpolator.cubic(curvature = 0.35f),
    pointProvider = LineCartesianLayer.PointProvider.single(
        LineCartesianLayer.Point(
            component = ShapeComponent(
                fill = Fill(color),
                shape = androidx.compose.foundation.shape.CircleShape,
            ),
            size = 4.dp,
        )
    ),
)

/** Integer-only y formatter - no "1.0" noise. */
val integerFormatter: CartesianValueFormatter = CartesianValueFormatter { _, v, _ ->
    v.toInt().toString()
}

/** Themed start axis with integer labels and dashed grid. */
@Composable
fun themedStartAxis(
    formatter: CartesianValueFormatter = integerFormatter,
    showGuideline: Boolean = true,
): VerticalAxis<Axis.Position.Vertical.Start> =
    VerticalAxis.rememberStart(
        line = chartAxisLine(),
        label = chartLabelComponent(),
        tick = chartAxisTick(),
        guideline = if (showGuideline) chartGuideline() else null,
        valueFormatter = formatter,
        itemPlacer = VerticalAxis.ItemPlacer.count(count = { 4 }),
    )

/** Themed bottom axis - caller supplies label formatter (week names, dates...). */
@Composable
fun themedBottomAxis(
    formatter: CartesianValueFormatter,
): HorizontalAxis<Axis.Position.Horizontal.Bottom> =
    HorizontalAxis.rememberBottom(
        line = chartAxisLine(),
        label = chartLabelComponent(),
        tick = chartAxisTick(),
        guideline = null,
        valueFormatter = formatter,
    )

@Composable
fun themedColumnProvider(
    color: Color = MaterialTheme.colorScheme.primary,
): ColumnCartesianLayer.ColumnProvider =
    ColumnCartesianLayer.ColumnProvider.series(
        rememberLineComponent(
            fill = Fill(color),
            thickness = 6.dp,
            shape = RoundedCornerShape(3.dp),
        )
    )
