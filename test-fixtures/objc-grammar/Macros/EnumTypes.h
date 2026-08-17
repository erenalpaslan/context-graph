#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef NS_ENUM(NSInteger, WidgetKind) {
    WidgetKindSimple,
    WidgetKindComposite
};

@interface WidgetFactory : NSObject

- (id)makeWidgetOfKind:(WidgetKind)kind;

@end

NS_ASSUME_NONNULL_END
