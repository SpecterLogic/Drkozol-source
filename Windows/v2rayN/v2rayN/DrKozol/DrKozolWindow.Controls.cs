using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;

namespace v2rayN.DrKozol;

public sealed partial class DrKozolWindow
{
    private FrameworkElement PowerRing(Button button)
    {
        var wrapper = new Grid { Width = 156, Height = 156, Margin = button.Margin, HorizontalAlignment = HorizontalAlignment.Center };
        button.Margin = new Thickness(0); wrapper.Children.Add(button);
        var ring = new Ellipse { Width = 152, Height = 152, Stroke = _client.Connected ? Accent : Stroke,
            StrokeThickness = 3, IsHitTestVisible = false, RenderTransformOrigin = new Point(.5, .5) };
        if (_client.IsTesting || _client.IsConnecting)
        {
            ring.Stroke = Accent; ring.StrokeDashArray = new DoubleCollection { 28, 15 };
            var rotation = new RotateTransform(); ring.RenderTransform = rotation;
            rotation.BeginAnimation(RotateTransform.AngleProperty, new DoubleAnimation(0, 360, TimeSpan.FromSeconds(1.4)) { RepeatBehavior = RepeatBehavior.Forever });
            ring.Unloaded += (_, _) => rotation.BeginAnimation(RotateTransform.AngleProperty, null);
        }
        wrapper.Children.Add(ring); return wrapper;
    }

    private void InstallScrollStyle()
    {
        // Shared application resources also cover owned settings dialogs and popup scrollbars.
        Application.Current.Resources["DrKozol.ScrollThumb"] = Muted;
        if (Application.Current.Resources.Contains("DrKozol.ArrowlessScrollInstalled")) return;
        var style = (Style)System.Windows.Markup.XamlReader.Parse("""
          <Style xmlns="http://schemas.microsoft.com/winfx/2006/xaml/presentation"
                 xmlns:x="http://schemas.microsoft.com/winfx/2006/xaml" TargetType="{x:Type ScrollBar}">
            <Setter Property="Width" Value="9"/>
            <Setter Property="Background" Value="Transparent"/>
            <Setter Property="Template"><Setter.Value><ControlTemplate TargetType="{x:Type ScrollBar}">
              <Track x:Name="PART_Track" IsDirectionReversed="True" Orientation="{TemplateBinding Orientation}">
                <Track.DecreaseRepeatButton><RepeatButton Command="ScrollBar.PageUpCommand" Opacity="0" Focusable="False"/></Track.DecreaseRepeatButton>
                <Track.Thumb><Thumb MinHeight="24"><Thumb.Template><ControlTemplate TargetType="{x:Type Thumb}">
                  <Border Margin="2" CornerRadius="3" Background="{DynamicResource DrKozol.ScrollThumb}" Opacity="0.6"/>
                </ControlTemplate></Thumb.Template></Thumb></Track.Thumb>
                <Track.IncreaseRepeatButton><RepeatButton Command="ScrollBar.PageDownCommand" Opacity="0" Focusable="False"/></Track.IncreaseRepeatButton>
              </Track>
              <ControlTemplate.Triggers><Trigger Property="Orientation" Value="Horizontal">
                <Setter TargetName="PART_Track" Property="IsDirectionReversed" Value="False"/>
              </Trigger></ControlTemplate.Triggers>
            </ControlTemplate></Setter.Value></Setter>
            <Style.Triggers><Trigger Property="Orientation" Value="Horizontal"><Setter Property="Width" Value="Auto"/><Setter Property="Height" Value="9"/></Trigger></Style.Triggers>
          </Style>
          """);
        Application.Current.Resources[typeof(ScrollBar)] = style;
        Application.Current.Resources["DrKozol.ArrowlessScrollInstalled"] = true;
    }
}
