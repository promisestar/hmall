import request from './index'
import type { Item, PageResult, PageQuery, SearchParams, SearchFilters } from '@/types'

export function getItemPage(params: PageQuery): Promise<PageResult<Item>> {
  return request.get('/items/page', { params })
}

export function getItemById(id: number): Promise<Item> {
  return request.get(`/items/${id}`)
}

export function queryItemsByIds(ids: number[]): Promise<Item[]> {
  return request.get('/items', { params: { ids: ids.join(',') } })
}

export function saveItem(data: Item): Promise<void> {
  return request.post('/items', data)
}

export function updateItem(data: Item): Promise<void> {
  return request.put('/items', data)
}

export function updateItemStatus(id: number, status: number): Promise<void> {
  return request.put(`/items/status/${id}/${status}`)
}

export function deleteItem(id: number): Promise<void> {
  return request.delete(`/items/${id}`)
}

export function searchList(params: SearchParams): Promise<PageResult<Item>> {
  return request.get('/search/list', { params })
}

export function searchFilters(params: Partial<SearchParams>): Promise<SearchFilters> {
  return request.post('/search/filters', params)
}

export function searchSuggestion(key: string): Promise<string[]> {
  return request.get('/search/suggestion', { params: { key } })
}

export function deductStock(items: { itemId: number; num: number }[]): Promise<void> {
  return request.put('/items/stock/deduct', items)
}
