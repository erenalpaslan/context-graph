#import "Widget.h"

@implementation Widget

- (instancetype)initWithName:(NSString *)name {
    self = [super init];
    return self;
}

- (void)doThing:(id)a with:(id)b {
}

+ (instancetype)widgetWithName:(NSString *)name {
    return [[Widget alloc] initWithName:name];
}

@end
