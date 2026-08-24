pub struct Invoice {
    pub total: i32,
}

impl Invoice {
    pub fn amount(&self) -> i32 {
        self.total
    }
}
