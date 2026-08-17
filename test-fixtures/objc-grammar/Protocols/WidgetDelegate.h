#import <Foundation/Foundation.h>

@protocol WidgetDelegate <NSObject>

- (void)widgetDidFinish:(id)sender;

@optional
- (void)widgetDidFail;

@end
