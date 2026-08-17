package billing

type Invoice struct {
	Total int
}

func (i *Invoice) Amount() int {
	return i.Total
}
