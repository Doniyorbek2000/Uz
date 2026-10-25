import UIKit

enum Icon {
    case shift, shiftOn, shiftLocked, backspace, returnKey, search, send, next, done, globe, emoji, settings, hide, convert
}

/// Keyboard glyphs drawn from simple geometry on a 24×24 grid so every theme can tint them.
enum Icons {

    static func draw(_ icon: Icon, in context: CGContext, center: CGPoint, size: CGFloat, color: UIColor) {
        context.saveGState()
        context.translateBy(x: center.x - size / 2, y: center.y - size / 2)
        context.scaleBy(x: size / 24, y: size / 24)
        context.setStrokeColor(color.cgColor)
        context.setFillColor(color.cgColor)
        context.setLineWidth(1.9)
        context.setLineCap(.round)
        context.setLineJoin(.round)

        func line(_ points: [CGPoint]) {
            let path = UIBezierPath()
            path.move(to: points[0])
            points.dropFirst().forEach { path.addLine(to: $0) }
            context.addPath(path.cgPath)
            context.strokePath()
        }

        switch icon {
        case .shift, .shiftOn, .shiftLocked:
            let bottom: CGFloat = icon == .shiftLocked ? 16.5 : 19.5
            let path = UIBezierPath()
            path.move(to: CGPoint(x: 12, y: 3.5))
            path.addLine(to: CGPoint(x: 20.5, y: 12.5))
            path.addLine(to: CGPoint(x: 15.5, y: 12.5))
            path.addLine(to: CGPoint(x: 15.5, y: bottom))
            path.addLine(to: CGPoint(x: 8.5, y: bottom))
            path.addLine(to: CGPoint(x: 8.5, y: 12.5))
            path.addLine(to: CGPoint(x: 3.5, y: 12.5))
            path.close()
            context.addPath(path.cgPath)
            context.drawPath(using: icon == .shift ? .stroke : .fillStroke)
            if icon == .shiftLocked {
                context.fill(CGRect(x: 8.5, y: 19, width: 7, height: 1.5))
            }
        case .backspace:
            let path = UIBezierPath()
            path.move(to: CGPoint(x: 8, y: 5))
            path.addLine(to: CGPoint(x: 21, y: 5))
            path.addLine(to: CGPoint(x: 21, y: 19))
            path.addLine(to: CGPoint(x: 8, y: 19))
            path.addLine(to: CGPoint(x: 2.5, y: 12))
            path.close()
            context.addPath(path.cgPath)
            context.strokePath()
            line([CGPoint(x: 11.5, y: 9), CGPoint(x: 17.5, y: 15)])
            line([CGPoint(x: 17.5, y: 9), CGPoint(x: 11.5, y: 15)])
        case .returnKey:
            line([CGPoint(x: 19.5, y: 5.5), CGPoint(x: 19.5, y: 13), CGPoint(x: 5, y: 13)])
            line([CGPoint(x: 9, y: 9), CGPoint(x: 5, y: 13), CGPoint(x: 9, y: 17)])
        case .search:
            context.strokeEllipse(in: CGRect(x: 4.5, y: 4.5, width: 12, height: 12))
            line([CGPoint(x: 15, y: 15), CGPoint(x: 20, y: 20)])
        case .send:
            let path = UIBezierPath()
            path.move(to: CGPoint(x: 3.5, y: 20))
            path.addLine(to: CGPoint(x: 21, y: 12))
            path.addLine(to: CGPoint(x: 3.5, y: 4))
            path.addLine(to: CGPoint(x: 5.5, y: 10.5))
            path.addLine(to: CGPoint(x: 13, y: 12))
            path.addLine(to: CGPoint(x: 5.5, y: 13.5))
            path.close()
            context.addPath(path.cgPath)
            context.drawPath(using: .fillStroke)
        case .next:
            line([CGPoint(x: 4, y: 12), CGPoint(x: 19.5, y: 12)])
            line([CGPoint(x: 13.5, y: 6), CGPoint(x: 19.5, y: 12), CGPoint(x: 13.5, y: 18)])
        case .done:
            line([CGPoint(x: 4.5, y: 12.5), CGPoint(x: 9.5, y: 17.5), CGPoint(x: 19.5, y: 6.5)])
        case .globe:
            context.strokeEllipse(in: CGRect(x: 3.5, y: 3.5, width: 17, height: 17))
            context.strokeEllipse(in: CGRect(x: 8, y: 3.5, width: 8, height: 17))
            line([CGPoint(x: 3.5, y: 12), CGPoint(x: 20.5, y: 12)])
            line([CGPoint(x: 5, y: 7.5), CGPoint(x: 19, y: 7.5)])
            line([CGPoint(x: 5, y: 16.5), CGPoint(x: 19, y: 16.5)])
        case .emoji:
            context.strokeEllipse(in: CGRect(x: 3.5, y: 3.5, width: 17, height: 17))
            let smile = UIBezierPath()
            smile.move(to: CGPoint(x: 8, y: 14))
            smile.addQuadCurve(to: CGPoint(x: 16, y: 14), controlPoint: CGPoint(x: 12, y: 18.5))
            context.addPath(smile.cgPath)
            context.strokePath()
            context.fillEllipse(in: CGRect(x: 7.7, y: 8.7, width: 2.6, height: 2.6))
            context.fillEllipse(in: CGRect(x: 13.7, y: 8.7, width: 2.6, height: 2.6))
        case .settings:
            context.strokeEllipse(in: CGRect(x: 9, y: 9, width: 6, height: 6))
            context.strokeEllipse(in: CGRect(x: 5.5, y: 5.5, width: 13, height: 13))
            context.setLineWidth(2.6)
            for i in 0..<8 {
                let angle = Double.pi * Double(i) / 4
                let c = CGFloat(cos(angle))
                let s = CGFloat(sin(angle))
                line([CGPoint(x: 12 + c * 7, y: 12 + s * 7), CGPoint(x: 12 + c * 9.5, y: 12 + s * 9.5)])
            }
        case .hide:
            line([CGPoint(x: 6, y: 9.5), CGPoint(x: 12, y: 15.5), CGPoint(x: 18, y: 9.5)])
        case .convert:
            line([CGPoint(x: 4, y: 8), CGPoint(x: 18.5, y: 8)])
            line([CGPoint(x: 15, y: 4.5), CGPoint(x: 18.5, y: 8), CGPoint(x: 15, y: 11.5)])
            line([CGPoint(x: 20, y: 16), CGPoint(x: 5.5, y: 16)])
            line([CGPoint(x: 9, y: 12.5), CGPoint(x: 5.5, y: 16), CGPoint(x: 9, y: 19.5)])
        }
        context.restoreGState()
    }
}
