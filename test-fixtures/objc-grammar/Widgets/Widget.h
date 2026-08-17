#import <Foundation/Foundation.h>
#import "../Protocols/WidgetDelegate.h"

@interface Widget : NSObject <NSCopying>

@property (nonatomic, strong) NSString *name;
@property (nonatomic, copy, nullable) NSArray<NSString *> *tags;
@property (nonatomic, copy) void (^completion)(BOOL success);

- (instancetype)initWithName:(NSString *)name;
- (void)doThing:(id)a with:(id)b;
- (void)fetchWithCompletion:(void (^)(BOOL success))completion;
+ (instancetype)widgetWithName:(NSString *)name;

@end
